package com.snapreel.app.util

import android.content.Context
import com.snapreel.app.BuildConfig
import com.snapreel.app.util.update.UpdateFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateInfo(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long
)

/** Why an update check failed. */
sealed interface CheckFailure {
    data object NoNetwork : CheckFailure
    data object Timeout : CheckFailure
    data object RateLimited : CheckFailure
    data class HttpError(val code: Int) : CheckFailure
    data object InvalidRelease : CheckFailure
}

/** The short reason shown in "Couldn't check for updates (reason)". */
val CheckFailure.reasonText: String
    get() = when (this) {
        CheckFailure.NoNetwork -> "no internet connection"
        CheckFailure.Timeout -> "the server didn't respond in time"
        CheckFailure.RateLimited -> "too many checks, try again later"
        is CheckFailure.HttpError -> "server error $code"
        CheckFailure.InvalidRelease -> "the latest release has no app file"
    }

/** The outcome of an update check. A failure is never reported as "up to date". */
sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val reason: CheckFailure) : UpdateCheckResult
}

/** Pure mapping from the GitHub "latest release" response to an [UpdateCheckResult]. */
object UpdateCheckMapping {

    /**
     * - 200 with a newer tag and an APK asset → [UpdateCheckResult.Available] (same fields as before).
     * - 200 with a tag that isn't newer → [UpdateCheckResult.UpToDate].
     * - 200 with a newer tag but no APK asset, or a body that isn't a release → `InvalidRelease`.
     * - 429, or 403 with `X-RateLimit-Remaining: 0` → `RateLimited`; any other non-200 → `HttpError`.
     */
    fun mapCheckResponse(
        code: Int,
        headers: Map<String, List<String>>,
        body: String?,
        currentVersion: String,
    ): UpdateCheckResult {
        if (code != HttpURLConnection.HTTP_OK) {
            val remaining = headers.entries
                .firstOrNull { it.key.equals("X-RateLimit-Remaining", ignoreCase = true) }
                ?.value?.firstOrNull()?.trim()
            return if (code == 429 || (code == HttpURLConnection.HTTP_FORBIDDEN && remaining == "0")) {
                UpdateCheckResult.Failed(CheckFailure.RateLimited)
            } else {
                UpdateCheckResult.Failed(CheckFailure.HttpError(code))
            }
        }
        return try {
            val json = JSONObject(body ?: return UpdateCheckResult.Failed(CheckFailure.InvalidRelease))

            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val releaseTitle = json.optString("name", "New Update Available")
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")
            val current = currentVersion.removePrefix("v").trim()

            if (!isNewerVersion(tagName, current)) return UpdateCheckResult.UpToDate

            val assets = json.optJSONArray("assets") ?: return UpdateCheckResult.Failed(CheckFailure.InvalidRelease)
            var apkUrl: String? = null
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                // getJSONObject (not opt…) as before: a malformed asset list is an invalid release.
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return UpdateCheckResult.Failed(CheckFailure.InvalidRelease)

            UpdateCheckResult.Available(
                AppUpdateInfo(
                    versionName = tagName,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkUrl,
                    apkSize = apkSize,
                )
            )
        } catch (_: JSONException) {
            UpdateCheckResult.Failed(CheckFailure.InvalidRelease)
        }
    }

    /** A timeout → `Timeout`; any other I/O failure (no network, DNS, refused) → `NoNetwork`. */
    fun mapCheckException(e: Throwable): UpdateCheckResult.Failed = UpdateCheckResult.Failed(
        when (e) {
            is SocketTimeoutException -> CheckFailure.Timeout
            is JSONException -> CheckFailure.InvalidRelease
            else -> CheckFailure.NoNetwork
        }
    )

    /** Dot-separated numeric comparison (unchanged since the first release). */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        if (remoteVersion.isBlank() || currentVersion.isBlank()) return false
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val remote = remoteParts.getOrElse(i) { 0 }
            val current = currentParts.getOrElse(i) { 0 }
            if (remote > current) return true
            if (remote < current) return false
        }
        return false
    }
}

/** Where update checks and downloads come from (a seam for coordinator tests). */
interface UpdateSource {
    suspend fun checkForUpdates(): UpdateCheckResult

    /** Downloads the release APK; progress is (downloaded, total) bytes. Returns the complete file. */
    suspend fun download(info: AppUpdateInfo, onProgress: (Long, Long) -> Unit): File
}

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: UpdateFiles,
) : UpdateSource {
    companion object {
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/shahriar-ahmed-seam/SnapReel/releases/latest"

        /** Where users download a release by hand (used when a reinstall is needed). */
        const val RELEASES_PAGE = "https://github.com/shahriar-ahmed-seam/SnapReel/releases/latest"

        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_STEP_BYTES = 256L * 1024
    }

    override suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(GITHUB_API_LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "SnapReel-App")
            }
            val code = connection.responseCode
            val body = if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            UpdateCheckMapping.mapCheckResponse(code, headers, body, BuildConfig.VERSION_NAME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheckMapping.mapCheckException(e)
        } finally {
            connection?.disconnect()
        }
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean =
        UpdateCheckMapping.isNewerVersion(remoteVersion, currentVersion)

    /**
     * Streams the APK to `cacheDir/updates/snapreel-<version>.apk.part` (following GitHub's
     * redirects), then renames it to `.apk` once complete. The `.part` is deleted on any failure
     * or cancellation, so a partial file is never mistaken for an update.
     */
    override suspend fun download(info: AppUpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val part = files.partFileFor(info.versionName)
            val apk = files.apkFileFor(info.versionName)
            files.dir.mkdirs()
            part.delete()
            apk.delete()
            var connection: HttpURLConnection? = null
            try {
                var currentUrl = info.downloadUrl
                var redirects = 0
                while (true) {
                    connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "SnapReel-App")
                    }
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        currentUrl = connection.getHeaderField("Location")
                            ?: throw IOException("Redirect without a location")
                        connection.disconnect()
                        if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw IOException("Download failed (HTTP $status)")
                    break
                }

                val conn = connection!!
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                onProgress(0L, total)
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        output.fd.sync()
                        onProgress(downloaded, total)
                    }
                }
                if (!part.renameTo(apk)) throw IOException("Couldn't save the update")
                apk
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                connection?.disconnect()
            }
        }
}
