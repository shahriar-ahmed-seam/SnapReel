package com.snapreel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.preservation.LegacyOracles
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.CheckFailure
import com.snapreel.app.util.UpdateCheckMapping
import com.snapreel.app.util.UpdateCheckResult
import com.snapreel.app.util.reasonText
import com.snapreel.app.util.update.CheckStatus
import com.snapreel.app.util.update.UpdateReducer
import com.snapreel.app.util.update.UpdateUiState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Property 8: Bug Condition - Update check results are distinguished.
 *
 * A failed check is never reported as "up to date": only a successful response whose tag isn't
 * newer is [UpdateCheckResult.UpToDate], and only a newer tag with an APK is an offer. Runs under
 * Robolectric for Android's `org.json`.
 *
 * **Validates: Requirements 2.20, 3.13**
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckPropertyTest {

    private val current = BuildConfig.VERSION_NAME

    private val versionArb: Arb<String> = Arb.bind(Arb.int(0..3), Arb.int(0..5), Arb.int(0..8), Arb.boolean()) { a, b, c, v ->
        (if (v) "v" else "") + "$a.$b.$c"
    }

    private val assetArb: Arb<JSONObject> = Arb.bind(
        Arb.element("app-release.apk", "SnapReel.APK", "notes.txt", "source.zip", "app.apk.sha256"),
        Arb.long(0L..40_000_000L),
    ) { name, size ->
        JSONObject().put("name", name).put("size", size).put("browser_download_url", "https://example.invalid/$name")
    }

    /** A release body: a tag, optional title/notes and an asset list. */
    private val releaseArb: Arb<Pair<String, String>> = Arb.bind(
        versionArb, Arb.list(assetArb, 0..4), Arb.boolean(), Arb.boolean(),
    ) { tag, assets, withTitle, withNotes ->
        val json = JSONObject().put("tag_name", tag).put("assets", JSONArray(assets))
        if (withTitle) json.put("name", "SnapReel $tag")
        if (withNotes) json.put("body", "Notes for $tag")
        tag to json.toString()
    }

    private val headersArb: Arb<Map<String, List<String>>> = Arb.choice(
        Arb.constant(emptyMap()),
        Arb.element("0", "1", "59").map { mapOf("X-RateLimit-Remaining" to listOf(it)) },
        Arb.element("0", "12").map { mapOf("x-ratelimit-remaining" to listOf(it)) },
    )

    @Test
    fun nonOkResponsesAreFailuresWithTheRightReason() = runBlocking<Unit> {
        val codes = Arb.choice(Arb.element(301, 304, 401, 403, 404, 429, 500, 502, 503), Arb.int(100..599))
        checkAll(1_000, codes, headersArb, releaseArb) { code, headers, (_, body) ->
            if (code == 200) return@checkAll
            val result = UpdateCheckMapping.mapCheckResponse(code, headers, body, current)
            val remaining = headers.entries.firstOrNull { it.key.equals("X-RateLimit-Remaining", true) }?.value?.first()
            val expected = if (code == 429 || (code == 403 && remaining == "0")) CheckFailure.RateLimited else CheckFailure.HttpError(code)
            assertEquals("HTTP $code $headers", UpdateCheckResult.Failed(expected), result)
        }
    }

    @Test
    fun okResponsesOfferOnlyNewerReleasesWithAnApk() = runBlocking<Unit> {
        checkAll(1_000, releaseArb, headersArb) { (tag, body), headers ->
            val result = UpdateCheckMapping.mapCheckResponse(200, headers, body, current)
            val newer = LegacyOracles.isNewerVersion(tag.removePrefix("v").trim(), current.removePrefix("v").trim())
            val offer = LegacyOracles.parseReleaseOffer(200, body, current)
            when {
                !newer -> assertEquals("tag $tag over $current", UpdateCheckResult.UpToDate, result)
                offer == null -> assertEquals(
                    "a newer tag without an APK is an invalid release: $body",
                    UpdateCheckResult.Failed(CheckFailure.InvalidRelease), result,
                )
                else -> assertEquals(
                    "the offer keeps the same fields as before: $body",
                    UpdateCheckResult.Available(
                        AppUpdateInfo(offer.versionName, offer.releaseTitle, offer.releaseNotes, offer.downloadUrl, offer.apkSize)
                    ),
                    result,
                )
            }
        }
    }

    @Test
    fun malformedBodiesAreInvalidReleases() = runBlocking<Unit> {
        val garbage = Arb.choice(
            Arb.string(0..40),
            Arb.element("", "[]", "null", "{\"tag_name\": ", "<html>rate limited</html>", "{\"tag_name\":\"9.9.9\",\"assets\":[1,2]}"),
        )
        checkAll(1_000, garbage) { body ->
            val result = UpdateCheckMapping.mapCheckResponse(200, emptyMap(), body, current)
            assertNotEquals("garbage must never look like an available update: $body", true, result is UpdateCheckResult.Available)
            if (result !is UpdateCheckResult.UpToDate) {
                assertEquals("garbage body: $body", UpdateCheckResult.Failed(CheckFailure.InvalidRelease), result)
            }
        }
    }

    @Test
    fun exceptionsMapToNetworkReasons() = runBlocking<Unit> {
        val exceptions = Arb.element(
            listOf<() -> Throwable>(
                { SocketTimeoutException("read timed out") },
                { UnknownHostException("api.github.com") },
                { ConnectException("refused") },
                { IOException("reset") },
                { SSLHandshakeException("bad cert") },
            )
        )
        checkAll(200, exceptions) { make ->
            val e = make()
            val expected = if (e is SocketTimeoutException) CheckFailure.Timeout else CheckFailure.NoNetwork
            assertEquals(e.toString(), UpdateCheckResult.Failed(expected), UpdateCheckMapping.mapCheckException(e))
        }
    }

    @Test
    fun failuresAreNeverShownAsUpToDateAndOnlyOffersOpenTheDialog() = runBlocking<Unit> {
        val failure = Arb.choice(
            Arb.element(CheckFailure.NoNetwork, CheckFailure.Timeout, CheckFailure.RateLimited, CheckFailure.InvalidRelease),
            Arb.int(100..599).map { CheckFailure.HttpError(it) },
        )
        checkAll(500, failure) { reason ->
            val status = UpdateReducer.checkStatusFor(UpdateCheckResult.Failed(reason))
            assertEquals(CheckStatus.Failed(reason), status)
            assertTrue("the failure reason is spelled out", reason.reasonText.isNotBlank())
            // Home: a failed or up-to-date check never opens the dialog.
            assertEquals(UpdateUiState.Hidden, UpdateReducer.afterCheck(UpdateUiState.Hidden, UpdateCheckResult.Failed(reason)))
        }
        assertEquals(UpdateUiState.Hidden, UpdateReducer.afterCheck(UpdateUiState.Hidden, UpdateCheckResult.UpToDate))
        assertEquals(CheckStatus.UpToDate, UpdateReducer.checkStatusFor(UpdateCheckResult.UpToDate))
        val info = AppUpdateInfo("9.0.0", "t", "n", "https://example.invalid/a.apk", 10)
        assertEquals(UpdateUiState.Offer(info), UpdateReducer.afterCheck(UpdateUiState.Hidden, UpdateCheckResult.Available(info)))
        // A check never replaces an update that is already downloading.
        val downloading = UpdateUiState.Downloading(info, 5, 10)
        assertEquals(downloading, UpdateReducer.afterCheck(downloading, UpdateCheckResult.Available(info)))
    }
}
