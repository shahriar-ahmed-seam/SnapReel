package com.snapreel.app.preservation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.testsupport.Adapters
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Property 2: Preservation - the update offer (3.13).
 *
 * `isNewerVersion` is REAL (`UpdateManager`). The response handling is inline in the
 * network-bound `checkForUpdates()`, so the offer is ROUTED to [LegacyOracles.parseReleaseOffer]
 * until 7.2 extracts `mapCheckResponse`. Runs under Robolectric for Android's `org.json`.
 *
 * Observed on the UNFIXED code (`isNewerVersion(remote, current)`):
 * - Numeric parts compare left to right, missing parts count as 0: 1.2.7 > 1.2.6, 1.10 > 1.9,
 *   1.2.6.1 > 1.2.6, 2 > 1.9.9; 1.2.6 vs 1.2.6 / 1.2.6.0 is not newer (either way round).
 * - Non-numeric parts are dropped, shifting the rest: "1.3.0-beta" → [1, 3] > 1.2.6, but
 *   "1.2.7-beta" → [1, 2] is not newer than 1.2.6; an unstripped "v1.3.0" → [3, 0] is newer.
 *   ("abc", "1.0") is false, ("1.0", "abc") is true. A blank side is false.
 * - Offer: the tag loses a leading "v" and is trimmed; title defaults to "New Update Available",
 *   notes to "Bug fixes and performance improvements."; the first asset ending in ".apk" (any
 *   case) supplies the URL and size (0 when absent). A tag that isn't newer gives no offer.
 */
@RunWith(AndroidJUnit4::class)
class UpdateOfferPreservationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val version: Arb<String> = Arb.choice(
        // Structured: 1–4 numeric parts, optional "v" prefix and suffix.
        arbitrary {
            val parts = Arb.list(Arb.choice(Arb.int(0..3), Arb.int(0..20), Arb.int(0..Int.MAX_VALUE)), 1..4).bind()
            val prefix = Arb.element("", "", "v", "V", " ").bind()
            val suffix = Arb.element("", "", "", "-beta", "-rc1", ".x", " ", "..", "99999999999").bind()
            prefix + parts.joinToString(".") + suffix
        },
        Arb.string(0..12, Codepoint.ascii()),
        Arb.stringPattern("[0-9.v -]{0,10}"),
        Arb.element("", " ", "1.2.6", Adapters.installedVersionName),
    )

    @Test
    fun update_isNewerVersionMatchesLegacy() = runTest {
        checkAll(1_000, version, version) { remote, current ->
            assertEquals(
                "isNewerVersion(\"$remote\", \"$current\")",
                LegacyOracles.isNewerVersion(remote, current),
                Adapters.isNewerVersion(context, remote, current),
            )
            // Equal strings are never newer.
            assertEquals(false, Adapters.isNewerVersion(context, current, current))
        }
    }

    private val simpleVersion: Arb<List<Int>> = Arb.list(Arb.int(0..20), 1..4)

    private fun asset(name: String, url: String?, size: Long?) = JSONObject().apply {
        put("name", name)
        if (url != null) put("browser_download_url", url)
        if (size != null) put("size", size)
        put("content_type", "application/octet-stream")
    }

    private val otherAsset = arbitrary {
        val name = Arb.element("source.zip", "checksums.txt", "SnapReel.apk.sha256", "notes.md", "apk").bind()
        asset(name, "https://github.com/shahriar-ahmed-seam/SnapReel/releases/download/x/$name", Arb.long(0L..1_000_000L).bind())
    }

    private val apkAsset = arbitrary {
        val name = Arb.element("SnapReel.apk", "SnapReel-release.APK", "app-release.Apk", "snapreel-1.3.0.apk").bind()
        val url = "https://github.com/shahriar-ahmed-seam/SnapReel/releases/download/" +
            Arb.stringPattern("[A-Za-z0-9._-]{1,12}").bind() + "/$name"
        asset(name, url, Arb.long(0L..200_000_000L).orNull(0.2).bind())
    }

    /** A release body: tag, optional title/notes, assets with at least one APK (non-APKs may come first). */
    private fun release(tag: String) = arbitrary {
        val json = JSONObject()
        json.put("tag_name", tag)
        Arb.stringPattern("[A-Za-z0-9 .:!-]{0,30}").orNull(0.3).bind()?.let { json.put("name", it) }
        Arb.stringPattern("[A-Za-z0-9 .\n*#-]{0,80}").orNull(0.3).bind()?.let { json.put("body", it) }
        json.put("draft", false)
        json.put("prerelease", Arb.boolean().bind())
        val assets = Arb.list(otherAsset, 0..3).bind() + apkAsset.bind() + Arb.list(Arb.choice(otherAsset, apkAsset), 0..2).bind()
        json.put("assets", JSONArray(assets))
        json.toString()
    }

    /** A newer tag with an APK asset yields the same offer fields. */
    @Test
    fun update_newerTagWithApkYieldsSameOffer() = runTest {
        checkAll(1_000, simpleVersion, Arb.int(0..3), Arb.int(1..5), Arb.element("", "v"), Arb.int(0..1_000_000)) { current, at, bump, prefix, seed ->
            val pos = at.coerceAtMost(current.size)
            val newer = current.take(pos) + ((current.getOrElse(pos) { 0 }) + bump)
            val tag = prefix + newer.joinToString(".")
            val currentName = current.joinToString(".")
            check(LegacyOracles.isNewerVersion(tag.removePrefix("v"), currentName)) { "generator: $tag must be newer than $currentName" }

            val body = release(tag).sample(RandomSource.seeded(seed.toLong())).value
            val expected = LegacyOracles.parseReleaseOffer(200, body, currentName)
            assertNotNull("oracle: a newer tag with an APK must yield an offer: $body", expected)
            assertEquals("offer for $body (current $currentName)", expected, Adapters.releaseOffer(200, body, currentName))
        }
    }

    /** A tag that isn't newer yields no offer (no dialog), even with an APK asset. */
    @Test
    fun update_tagNotNewerYieldsNoOffer() = runTest {
        checkAll(1_000, simpleVersion, Arb.int(0..3), Arb.element("", "v"), Arb.int(0..1_000_000)) { current, down, prefix, seed ->
            val older = when (down) {
                0 -> current                                   // same version
                1 -> current + 0                               // same, with a trailing .0
                else -> current.mapIndexed { i, p -> if (i == current.lastIndex) p - 1 else p }.map { it.coerceAtLeast(0) }
            }
            val tag = prefix + older.joinToString(".")
            val currentName = current.joinToString(".")
            if (LegacyOracles.isNewerVersion(tag.removePrefix("v"), currentName)) return@checkAll

            val body = release(tag).sample(RandomSource.seeded(seed.toLong())).value
            assertNull("oracle: no offer for $tag over $currentName", LegacyOracles.parseReleaseOffer(200, body, currentName))
            assertNull("no offer for $tag over $currentName", Adapters.releaseOffer(200, body, currentName))
        }
    }

    @Test
    fun update_observedSamples() {
        fun newer(r: String, c: String) = Adapters.isNewerVersion(context, r, c)
        assertEquals(true, newer("1.2.7", "1.2.6"))
        assertEquals(true, newer("1.3.0", "1.2.6"))
        assertEquals(false, newer("1.2.6", "1.2.6"))
        assertEquals(false, newer("1.2.5", "1.2.6"))
        assertEquals(true, newer("1.10", "1.9"))
        assertEquals(true, newer("1.2.6.1", "1.2.6"))
        assertEquals(false, newer("1.2.6", "1.2.6.0"))
        assertEquals(false, newer("1.2.6.0", "1.2.6"))
        assertEquals(true, newer("2", "1.9.9"))
        assertEquals(true, newer("1.3.0-beta", "1.2.6"))
        assertEquals(false, newer("1.2.7-beta", "1.2.6"))
        assertEquals(true, newer("v1.3.0", "1.2.6"))
        assertEquals(false, newer("abc", "1.0"))
        assertEquals(true, newer("1.0", "abc"))
        assertEquals(false, newer("", "1.2.6"))
        assertEquals(false, newer("1.3.0", " "))

        val apkUrl = "https://github.com/shahriar-ahmed-seam/SnapReel/releases/download/v1.3.0/SnapReel-1.3.0.apk"
        val full = JSONObject()
            .put("tag_name", "v1.3.0")
            .put("name", "SnapReel 1.3.0")
            .put("body", "Fixes")
            .put(
                "assets",
                JSONArray()
                    .put(asset("checksums.txt", "https://example.invalid/checksums.txt", 10))
                    .put(asset("SnapReel-1.3.0.apk", apkUrl, 12_345_678))
                    .put(asset("other.apk", "https://example.invalid/other.apk", 1)),
            )
        assertEquals(
            LegacyOracles.Offer("1.3.0", "SnapReel 1.3.0", "Fixes", apkUrl, 12_345_678),
            Adapters.releaseOffer(200, full.toString(), "1.2.6"),
        )
        val bare = JSONObject()
            .put("tag_name", "1.3.0")
            .put("assets", JSONArray().put(asset("SnapReel.APK", apkUrl, null)))
        assertEquals(
            LegacyOracles.Offer("1.3.0", "New Update Available", "Bug fixes and performance improvements.", apkUrl, 0),
            Adapters.releaseOffer(200, bare.toString(), "1.2.6"),
        )
        assertNull("not newer", Adapters.releaseOffer(200, full.put("tag_name", "v1.2.6").toString(), "1.2.6"))
    }
}
