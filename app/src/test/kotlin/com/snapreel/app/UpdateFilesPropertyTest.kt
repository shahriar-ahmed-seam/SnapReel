package com.snapreel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.util.update.PendingUpdate
import com.snapreel.app.util.update.UpdateFiles
import com.snapreel.app.util.update.UpdateFilesPolicy
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * Property 10: Bug Condition - Only stale update files are deleted.
 *
 * Startup cleanup keeps a complete, validated pending update that is newer than the installed
 * version (so an install interrupted by a process restart can still finish) and deletes
 * everything else: partial downloads, unrecorded or mismatched APKs, orphan or unreadable
 * sidecars, updates that aren't newer, and the pre-1.3 `snapreel_update*.apk` files.
 * Robolectric for Android's `org.json` (the sidecar format).
 *
 * **Validates: Requirements 2.19**
 */
@RunWith(AndroidJUnit4::class)
class UpdateFilesPropertyTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("update-files").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private enum class SidecarKind { NONE, VALID, GARBAGE, POINTS_TO_MISSING, WRONG_SIZE }

    private data class Scenario(
        val installedVc: Long,
        val apks: List<Pair<String, Long>>, // name to versionCode, in the updates dir
        val parts: Int,
        val strayFiles: Int,
        val legacyInCache: List<String>,
        val sidecar: SidecarKind,
        val sidecarVc: Long,
        val pickApk: Int,
    )

    private val scenarioArb: Arb<Scenario> = Arb.bind(
        Arb.long(10L..20L),
        Arb.list(Arb.long(5L..25L), 0..4),
        Arb.int(0..2),
        Arb.int(0..2),
        Arb.list(Arb.element("snapreel_update.apk", "snapreel_update_2.apk", "other.apk", "notes.txt", "SNAPREEL_update.apk"), 0..4),
        Arb.element(SidecarKind.entries),
        Arb.long(5L..25L),
        Arb.int(0..10),
    ) { vc, apkVcs, parts, strays, legacy, kind, sidecarVc, pick ->
        Scenario(vc, apkVcs.mapIndexed { i, v -> "snapreel-1.$i.$v.apk" to v }, parts, strays, legacy.distinct(), kind, sidecarVc, pick)
    }

    @Test
    fun startupCleanupDeletesExactlyTheStaleFiles() = runBlocking<Unit> {
        var run = 0
        checkAll(300, scenarioArb) { s ->
            val cacheDir = File(root, "run${run++}/cache").apply { mkdirs() }
            val externalCache = File(cacheDir.parentFile, "external-cache").apply { mkdirs() }
            val files = UpdateFiles(File(cacheDir, "updates"), listOf(cacheDir, externalCache))
            files.dir.mkdirs()

            // Updates dir contents.
            val apkFiles = s.apks.map { (name, _) -> File(files.dir, name).apply { writeBytes(ByteArray(100 + name.length)) } }
            repeat(s.parts) { File(files.dir, "snapreel-9.$it.apk.part").writeBytes(ByteArray(10)) }
            repeat(s.strayFiles) { File(files.dir, "stray$it.bin").writeBytes(ByteArray(3)) }
            // Legacy downloads next to other cache files that must survive.
            s.legacyInCache.forEachIndexed { i, name ->
                File(if (i % 2 == 0) cacheDir else externalCache, name).writeBytes(ByteArray(7))
            }
            File(cacheDir, "image_cache").mkdirs()

            val target = apkFiles.getOrNull(s.pickApk % (apkFiles.size.coerceAtLeast(1)))
            val sidecar = File(files.dir, UpdateFilesPolicy.SIDECAR)
            when (s.sidecar) {
                SidecarKind.NONE -> Unit
                SidecarKind.GARBAGE -> sidecar.writeText("{not json")
                SidecarKind.POINTS_TO_MISSING -> files.writePending(PendingUpdate("1.9", s.sidecarVc, 50, "t", "n", "snapreel-missing.apk"))
                SidecarKind.VALID, SidecarKind.WRONG_SIZE -> if (target != null) {
                    val size = if (s.sidecar == SidecarKind.VALID) target.length() else target.length() + 1
                    files.writePending(PendingUpdate("1.9", s.sidecarVc, size, "t", "n", target.name))
                }
            }

            // Expected: only a complete, recorded, newer pending update and its sidecar survive.
            val keepsPending = s.sidecar == SidecarKind.VALID && target != null && s.sidecarVc > s.installedVc
            val expectedUpdates = if (keepsPending) setOf(target!!.name, UpdateFilesPolicy.SIDECAR) else emptySet()
            val expectedCache = s.legacyInCache.filterIndexed { i, _ -> i % 2 == 0 }.filterNot(UpdateFilesPolicy::isLegacy).toSet() + "image_cache" + "updates"
            val expectedExternal = s.legacyInCache.filterIndexed { i, _ -> i % 2 == 1 }.filterNot(UpdateFilesPolicy::isLegacy).toSet()

            files.deleteStale(s.installedVc)

            val ctx = "$s"
            assertEquals("updates dir after cleanup: $ctx", expectedUpdates, files.dir.list()!!.toSet())
            assertEquals("cacheDir after cleanup: $ctx", expectedCache, cacheDir.list()!!.toSet())
            assertEquals("externalCacheDir after cleanup: $ctx", expectedExternal, externalCache.list()!!.toSet())

            val pending = files.pendingVerified(s.installedVc)
            if (keepsPending) {
                assertEquals(target!!.name, pending!!.second.name)
                assertEquals(s.sidecarVc, pending.first.versionCode)
            } else {
                assertNull("nothing to install: $ctx", pending)
            }
        }
    }

    @Test
    fun aPendingUpdateSurvivesRepeatedRestartsUntilInstalled() {
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val files = UpdateFiles(File(cacheDir, "updates"), listOf(cacheDir))
        files.dir.mkdirs()
        val apk = files.apkFileFor("1.3.1").apply { writeBytes(ByteArray(1234)) }
        files.writePending(PendingUpdate("1.3.1", 15, apk.length(), "t", "n", apk.name))

        repeat(3) { files.deleteStale(installedVc = 14) } // restarts before the install finishes
        assertEquals(apk.name, files.pendingVerified(14)!!.second.name)

        files.deleteStale(installedVc = 15) // the update is installed now
        assertEquals(emptySet<String>(), files.dir.list()!!.toSet())
    }

    @Test
    fun sidecarRoundTrips() = runBlocking<Unit> {
        checkAll(300, Arb.long(0L..Long.MAX_VALUE / 2), Arb.long(1L..1_000_000_000L), Arb.boolean()) { vc, size, withNotes ->
            val p = PendingUpdate("1.$vc", vc, size, "Title \"quoted\"", if (withNotes) "line1\nline2" else "", "snapreel-1.$vc.apk")
            assertEquals(p, UpdateFilesPolicy.parse(UpdateFilesPolicy.serialize(p)))
        }
    }
}
