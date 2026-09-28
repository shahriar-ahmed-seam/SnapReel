package com.snapreel.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.CheckFailure
import com.snapreel.app.util.UpdateCheckResult
import com.snapreel.app.util.UpdateManager
import com.snapreel.app.util.UpdateSource
import com.snapreel.app.util.update.ApkFacts
import com.snapreel.app.util.update.ApkFactsSource
import com.snapreel.app.util.update.CheckStatus
import com.snapreel.app.util.update.InstallStart
import com.snapreel.app.util.update.InstallStatusMapping
import com.snapreel.app.util.update.InstalledFacts
import com.snapreel.app.util.update.PackageInstallGateway
import com.snapreel.app.util.update.PendingUpdate
import com.snapreel.app.util.update.UpdateCoordinator
import com.snapreel.app.util.update.UpdateError
import com.snapreel.app.util.update.UpdateFiles
import com.snapreel.app.util.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The update flow end to end (1.18, 1.19): the real download (redirects, `.part` → `.apk`,
 * cancellation) against a local HTTP server, with fake APK inspection and a fake installer.
 *
 * **Validates: Requirements 2.17, 2.18, 2.19, 2.20**
 */
@RunWith(AndroidJUnit4::class)
class UpdateCoordinatorTest {

    /** A minimal HTTP/1.1 server on the loopback interface; [handler] writes the whole response. */
    private class TinyHttpServer(private val handler: (path: String, out: OutputStream) -> Unit) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "tiny-http") {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    thread(isDaemon = true) {
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            val requestLine = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            try {
                                handler(requestLine.split(" ")[1], s.getOutputStream())
                            } catch (_: IOException) {
                                // The client went away (e.g. a cancelled download).
                            }
                        }
                    }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:$port$path"
        override fun close() = server.close()
    }

    private fun OutputStream.respond(body: ByteArray, chunk: Int = body.size.coerceAtLeast(1), delayMs: Long = 0) {
        write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        flush()
        var offset = 0
        while (offset < body.size) {
            val n = minOf(chunk, body.size - offset)
            write(body, offset, n)
            flush()
            offset += n
            if (delayMs > 0) Thread.sleep(delayMs)
        }
    }

    private fun OutputStream.redirect(location: String) {
        write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        flush()
    }

    private class FakeInspector(var facts: ApkFacts?, var installed: InstalledFacts) : ApkFactsSource {
        override fun inspect(apk: File): ApkFacts? = facts
        override fun installed(): InstalledFacts = installed
    }

    private class FakeInstaller(var canRequest: Boolean, var start: InstallStart = InstallStart.Committed) : PackageInstallGateway {
        val installed = mutableListOf<File>()
        override fun canRequestPackageInstalls(): Boolean = canRequest
        override fun installPermissionIntent(): Intent = Intent("test.MANAGE_UNKNOWN_APP_SOURCES")
        override fun install(apk: File): InstallStart {
            installed += apk
            return start
        }
    }

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var files: UpdateFiles
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<TinyHttpServer>()

    private val installedVc = 14L
    private val pkg = "com.snapreel.app"
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = Files.createTempDirectory("coordinator").toFile()
        files = UpdateFiles(File(dir, "updates"), listOf(dir))
    }

    @After
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun server(handler: (String, OutputStream) -> Unit) = TinyHttpServer(handler).also { servers += it }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("timed out waiting for $what", condition())
    }

    private class Source(val check: () -> UpdateCheckResult, val manager: UpdateManager) : UpdateSource {
        val checks = AtomicInteger()
        override suspend fun checkForUpdates(): UpdateCheckResult {
            checks.incrementAndGet()
            return check()
        }
        override suspend fun download(info: AppUpdateInfo, onProgress: (Long, Long) -> Unit): File =
            manager.download(info, onProgress)
    }

    private fun coordinator(
        info: AppUpdateInfo?,
        inspector: FakeInspector,
        installer: FakeInstaller,
        check: () -> UpdateCheckResult = { if (info != null) UpdateCheckResult.Available(info) else UpdateCheckResult.UpToDate },
    ): Pair<UpdateCoordinator, Source> {
        val source = Source(check, UpdateManager(context, files))
        return UpdateCoordinator(source, files, inspector, installer, scope, installedVc, Dispatchers.IO) to source
    }

    private fun info(url: String, size: Long = apkBytes.size.toLong()) =
        AppUpdateInfo("1.3.1", "SnapReel 1.3.1", "Fixes", url, size)

    private val goodFacts get() = ApkFacts(pkg, installedVc + 1, setOf("releasekey"), null)
    private val installedFacts get() = InstalledFacts(pkg, installedVc, setOf("releasekey"))

    private fun UpdateCoordinator.offerAndStart() {
        check()
        waitFor("the offer") { state.value is UpdateUiState.Offer }
        startUpdate()
    }

    @Test
    fun permissionDenied_thenGranted_installsTheVerifiedDownload() {
        val http = server { path, out -> if (path == "/latest") out.redirect(servers[0].url("/app.apk")) else out.respond(apkBytes) }
        val installer = FakeInstaller(canRequest = false)
        val (c, _) = coordinator(info(http.url("/latest")), FakeInspector(goodFacts, installedFacts), installer)

        c.offerAndStart()
        waitFor("NeedsInstallPermission") { c.state.value is UpdateUiState.NeedsInstallPermission }
        val pending = files.pendingVerified(installedVc)
        assertNotNull("the verified download is recorded, so it survives a restart", pending)
        assertEquals(apkBytes.size.toLong(), pending!!.second.length())
        assertFalse("no partial file is left", files.partFileFor("1.3.1").exists())
        assertTrue("nothing is installed without the permission", installer.installed.isEmpty())

        // Returning without granting keeps asking.
        c.onInstallPermissionResult()
        assertTrue(c.state.value is UpdateUiState.NeedsInstallPermission)

        installer.canRequest = true
        c.onInstallPermissionResult()
        waitFor("Installing") { c.state.value is UpdateUiState.Installing }
        assertEquals(listOf(pending.second), installer.installed)

        c.onInstallOutcome(InstallStatusMapping.mapInstallStatus(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(UpdateUiState.Hidden, c.state.value)
    }

    @Test
    fun conflictFromTheInstaller_asksForTheOneTimeReinstall() {
        val http = server { _, out -> out.respond(apkBytes) }
        val installer = FakeInstaller(canRequest = true)
        val (c, _) = coordinator(info(http.url("/app.apk")), FakeInspector(goodFacts, installedFacts), installer)

        c.offerAndStart()
        waitFor("Installing") { c.state.value is UpdateUiState.Installing }
        c.onInstallOutcome(InstallStatusMapping.mapInstallStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict"))
        assertTrue(c.state.value is UpdateUiState.ReinstallRequired)
        waitFor("the pending update is cleared") { files.pendingVerified(installedVc) == null }
    }

    @Test
    fun signatureMismatchAtValidation_isCaughtBeforeInstalling() {
        val http = server { _, out -> out.respond(apkBytes) }
        val installer = FakeInstaller(canRequest = true)
        val facts = goodFacts.copy(signers = setOf("some-other-key"))
        val (c, _) = coordinator(info(http.url("/app.apk")), FakeInspector(facts, installedFacts), installer)

        c.offerAndStart()
        waitFor("ReinstallRequired") { c.state.value is UpdateUiState.ReinstallRequired }
        assertTrue("the installer is never launched", installer.installed.isEmpty())
        assertFalse("the rejected download is deleted", files.apkFileFor("1.3.1").exists())
        assertNull(files.pendingVerified(installedVc))
    }

    @Test
    fun incompleteDownload_failsWithASpecificErrorAndIsDeleted() {
        val http = server { _, out -> out.respond(apkBytes.copyOf(150_000)) }
        val installer = FakeInstaller(canRequest = true)
        val (c, _) = coordinator(info(http.url("/app.apk")), FakeInspector(goodFacts, installedFacts), installer)

        c.offerAndStart()
        waitFor("Failed") { c.state.value is UpdateUiState.Failed }
        val error = (c.state.value as UpdateUiState.Failed).error
        assertEquals(UpdateError.Incomplete(150_000, apkBytes.size.toLong()), error)
        assertTrue(error.message, error.message.contains("incomplete"))
        assertFalse(files.apkFileFor("1.3.1").exists())
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun dismissWhileDownloading_cancelsAndDeletesThePartialFile() {
        val http = server { _, out -> out.respond(ByteArray(4_000_000), chunk = 8 * 1024, delayMs = 10) }
        val (c, _) = coordinator(info(http.url("/app.apk"), 4_000_000), FakeInspector(goodFacts, installedFacts), FakeInstaller(true))

        c.offerAndStart()
        val part = files.partFileFor("1.3.1")
        waitFor("download progress") {
            val s = c.state.value
            s is UpdateUiState.Downloading && s.downloaded > 0 && part.exists()
        }
        c.dismiss()
        assertEquals(UpdateUiState.Hidden, c.state.value)
        waitFor("the partial file is deleted") { !part.exists() }
        assertFalse(files.apkFileFor("1.3.1").exists())
        Thread.sleep(100)
        assertEquals("a cancelled download never reopens the dialog", UpdateUiState.Hidden, c.state.value)
    }

    @Test
    fun afterAProcessRestart_theVerifiedDownloadIsOfferedWithoutRedownloading() {
        files.dir.mkdirs()
        val apk = files.apkFileFor("1.3.1").apply { writeBytes(apkBytes) }
        files.writePending(PendingUpdate("1.3.1", installedVc + 1, apk.length(), "SnapReel 1.3.1", "Fixes", apk.name))
        files.deleteStale(installedVc) // SnapReelApp.onCreate

        val installer = FakeInstaller(canRequest = true)
        val (c, source) = coordinator(null, FakeInspector(goodFacts, installedFacts), installer)
        c.restorePendingOrCheck()
        waitFor("ReadyToInstall") { c.state.value is UpdateUiState.ReadyToInstall }
        assertEquals("no network check while an install is pending", 0, source.checks.get())

        c.install()
        waitFor("Installing") { c.state.value is UpdateUiState.Installing }
        assertEquals(listOf(apk), installer.installed)
    }

    @Test
    fun installerLaunchFailure_isReportedNotSwallowed() {
        val http = server { _, out -> out.respond(apkBytes) }
        val installer = FakeInstaller(canRequest = true, start = InstallStart.Failed(UpdateError.LaunchFailed))
        val (c, _) = coordinator(info(http.url("/app.apk")), FakeInspector(goodFacts, installedFacts), installer)

        c.offerAndStart()
        waitFor("Failed") { c.state.value is UpdateUiState.Failed }
        assertEquals(UpdateError.LaunchFailed, (c.state.value as UpdateUiState.Failed).error)
        assertNotNull("the verified download is kept for a retry", files.pendingVerified(installedVc))
    }

    @Test
    fun failedCheck_isShownAsAFailureNotUpToDate() {
        val (c, _) = coordinator(null, FakeInspector(goodFacts, installedFacts), FakeInstaller(true)) {
            UpdateCheckResult.Failed(CheckFailure.NoNetwork)
        }
        c.check()
        waitFor("the check result") { c.checkStatus.value != CheckStatus.Checking && c.checkStatus.value != CheckStatus.Idle }
        assertEquals(CheckStatus.Failed(CheckFailure.NoNetwork), c.checkStatus.value)
        assertEquals("a failed check opens no dialog", UpdateUiState.Hidden, c.state.value)
    }
}
