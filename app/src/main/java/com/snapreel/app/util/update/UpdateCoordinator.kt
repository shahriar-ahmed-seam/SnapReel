package com.snapreel.app.util.update

import android.content.Intent
import com.snapreel.app.BuildConfig
import com.snapreel.app.di.ApplicationScope
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.CheckFailure
import com.snapreel.app.util.UpdateCheckResult
import com.snapreel.app.util.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What the update dialog shows. Every state except [Hidden] keeps the dialog open. */
sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Offer(val info: AppUpdateInfo) : UpdateUiState
    data class Downloading(val info: AppUpdateInfo, val downloaded: Long, val total: Long) : UpdateUiState {
        val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Verifying(val info: AppUpdateInfo) : UpdateUiState
    data class NeedsInstallPermission(val info: AppUpdateInfo) : UpdateUiState
    data class ReadyToInstall(val info: AppUpdateInfo) : UpdateUiState
    data class Installing(val info: AppUpdateInfo) : UpdateUiState
    data class Failed(val info: AppUpdateInfo, val error: UpdateError) : UpdateUiState
    data class ReinstallRequired(val info: AppUpdateInfo) : UpdateUiState
}

/** The Settings "Check for Updates" row. */
sealed interface CheckStatus {
    data object Idle : CheckStatus
    data object Checking : CheckStatus
    data object UpToDate : CheckStatus
    data class Failed(val reason: CheckFailure) : CheckStatus
}

const val REINSTALL_REQUIRED_MESSAGE =
    "This update is signed with a different key than the installed app, so Android can't install it " +
        "over your current version. Uninstall SnapReel, then install the new version once. " +
        "Future updates will install normally."

/** Pure transitions (design › In-app update: "Transitions go through a pure reducer"). */
object UpdateReducer {

    /** After validating a download: install (or ask for the permission first), or a specific failure. */
    fun afterValidation(info: AppUpdateInfo, validation: Validation, canRequestInstalls: Boolean): UpdateUiState =
        when (validation) {
            is Validation.Ok ->
                if (canRequestInstalls) UpdateUiState.Installing(info) else UpdateUiState.NeedsInstallPermission(info)
            is Validation.Incomplete -> UpdateUiState.Failed(info, UpdateError.Incomplete(validation.actual, validation.expected))
            Validation.Corrupt -> UpdateUiState.Failed(info, UpdateError.Corrupt)
            is Validation.WrongPackage -> UpdateUiState.Failed(info, UpdateError.WrongPackage)
            is Validation.NotNewer -> UpdateUiState.Failed(info, UpdateError.NotNewer)
            Validation.SignatureMismatch -> UpdateUiState.ReinstallRequired(info)
        }

    /** After the system installer reports back. */
    fun afterInstallOutcome(info: AppUpdateInfo, outcome: InstallOutcome): UpdateUiState = when (outcome) {
        InstallOutcome.PendingUserAction -> UpdateUiState.Installing(info)
        InstallOutcome.Installed -> UpdateUiState.Hidden
        InstallOutcome.ReinstallRequired -> UpdateUiState.ReinstallRequired(info)
        is InstallOutcome.Failed -> UpdateUiState.Failed(info, outcome.error)
    }

    /** After handing the APK to the installer. */
    fun afterInstallStart(info: AppUpdateInfo, start: InstallStart): UpdateUiState = when (start) {
        InstallStart.Committed, InstallStart.ViewIntentLaunched -> UpdateUiState.Installing(info)
        is InstallStart.Failed -> UpdateUiState.Failed(info, start.error)
    }

    /** The check result for the Settings row. */
    fun checkStatusFor(result: UpdateCheckResult): CheckStatus = when (result) {
        is UpdateCheckResult.Available -> CheckStatus.Idle
        UpdateCheckResult.UpToDate -> CheckStatus.UpToDate
        is UpdateCheckResult.Failed -> CheckStatus.Failed(result.reason)
    }

    /** Only an available update opens the dialog, and never over one that is already in progress. */
    fun afterCheck(current: UpdateUiState, result: UpdateCheckResult): UpdateUiState =
        if (result is UpdateCheckResult.Available &&
            (current is UpdateUiState.Hidden || current is UpdateUiState.Offer || current is UpdateUiState.Failed)
        ) {
            UpdateUiState.Offer(result.info)
        } else {
            current
        }
}

/**
 * The in-app update state machine: check → download → verify → install permission → install.
 * A singleton in the app scope, so a download survives moving between Home and Settings.
 */
@Singleton
class UpdateCoordinator(
    private val source: UpdateSource,
    private val files: UpdateFiles,
    private val inspector: ApkFactsSource,
    private val installer: PackageInstallGateway,
    private val scope: CoroutineScope,
    private val installedVersionCode: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        source: UpdateSource,
        files: UpdateFiles,
        inspector: ApkFactsSource,
        installer: PackageInstallGateway,
        @ApplicationScope scope: CoroutineScope,
    ) : this(source, files, inspector, installer, scope, BuildConfig.VERSION_CODE.toLong(), Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _checkStatus = MutableStateFlow<CheckStatus>(CheckStatus.Idle)
    val checkStatus: StateFlow<CheckStatus> = _checkStatus.asStateFlow()

    private var restored = false
    private var job: Job? = null

    fun installPermissionIntent(): Intent = installer.installPermissionIntent()

    /**
     * At launch: a verified pending download (kept across a process restart) is offered for
     * install right away; otherwise a silent check runs, which only opens the dialog for an update.
     */
    fun restorePendingOrCheck() {
        if (restored) return
        restored = true
        scope.launch {
            val pending = withContext(io) { files.pendingVerified(installedVersionCode) }
            if (pending != null) {
                val (p, file) = pending
                _state.value = UpdateUiState.ReadyToInstall(
                    AppUpdateInfo(p.versionName, p.title, p.notes, downloadUrl = "", apkSize = file.length())
                )
            } else {
                val result = source.checkForUpdates()
                _state.update { UpdateReducer.afterCheck(it, result) }
            }
        }
    }

    /** Settings > Check for Updates. */
    fun check() {
        if (_checkStatus.value == CheckStatus.Checking) return
        _checkStatus.value = CheckStatus.Checking
        scope.launch {
            val result = source.checkForUpdates()
            _checkStatus.value = UpdateReducer.checkStatusFor(result)
            _state.update { UpdateReducer.afterCheck(it, result) }
        }
    }

    /** "Update Now": download, verify, then install. */
    fun startUpdate() {
        val info = when (val s = _state.value) {
            is UpdateUiState.Offer -> s.info
            is UpdateUiState.Failed -> s.info
            else -> return
        }
        if (info.downloadUrl.isBlank()) return
        job?.cancel()
        _state.value = UpdateUiState.Downloading(info, 0L, info.apkSize)
        job = scope.launch {
            val file = try {
                source.download(info) { downloaded, total ->
                    _state.update { s ->
                        if (s is UpdateUiState.Downloading) s.copy(downloaded = downloaded, total = total) else s
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Failed(info, UpdateError.DownloadFailed(e.message))
                return@launch
            }
            verifyAndInstall(info, file)
        }
    }

    private suspend fun verifyAndInstall(info: AppUpdateInfo, file: File) {
        _state.value = UpdateUiState.Verifying(info)
        // Null when the validated download couldn't be recorded (storage full or unwritable).
        val validation: Validation? = withContext(io) {
            val facts = inspector.inspect(file)
            val result = UpdateValidator.validate(info.apkSize, file.length(), facts, inspector.installed())
            if (result is Validation.Ok) {
                try {
                    files.writePending(
                        PendingUpdate(
                            versionName = info.versionName,
                            versionCode = facts!!.versionCode,
                            sizeBytes = file.length(),
                            title = info.releaseTitle,
                            notes = info.releaseNotes,
                            fileName = file.name,
                        )
                    )
                } catch (_: IOException) {
                    file.delete()
                    return@withContext null
                }
            } else {
                // A download that failed validation is never kept.
                file.delete()
                files.clearPending()
            }
            result
        }
        if (validation == null) {
            _state.value = UpdateUiState.Failed(info, UpdateError.NoStorage)
            return
        }
        val next = UpdateReducer.afterValidation(info, validation, installer.canRequestPackageInstalls())
        if (next is UpdateUiState.Installing) install(info) else _state.value = next
    }

    /** "Install" (a verified pending update), or continuing after the permission was granted. */
    fun install() {
        val info = when (val s = _state.value) {
            is UpdateUiState.ReadyToInstall -> s.info
            is UpdateUiState.NeedsInstallPermission -> s.info
            is UpdateUiState.Failed -> s.info
            else -> return
        }
        if (!installer.canRequestPackageInstalls()) {
            _state.value = UpdateUiState.NeedsInstallPermission(info)
            return
        }
        scope.launch { install(info) }
    }

    private suspend fun install(info: AppUpdateInfo) {
        val pending = withContext(io) { files.pendingVerified(installedVersionCode) }
        if (pending == null) {
            _state.value = UpdateUiState.Failed(info, UpdateError.Corrupt)
            return
        }
        _state.value = UpdateUiState.Installing(info)
        val start = withContext(io) { installer.install(pending.second) }
        _state.update { current ->
            // The receiver may already have reported an outcome.
            if (current is UpdateUiState.Installing) UpdateReducer.afterInstallStart(info, start) else current
        }
    }

    /** Back from the "Install unknown apps" screen (or the app resumed while waiting for it). */
    fun onInstallPermissionResult() {
        val s = _state.value as? UpdateUiState.NeedsInstallPermission ?: return
        if (installer.canRequestPackageInstalls()) scope.launch { install(s.info) }
    }

    /** Retry after a failure: install again when a verified download is kept, else download again. */
    fun retry() {
        val s = _state.value as? UpdateUiState.Failed ?: return
        scope.launch {
            val pending = withContext(io) { files.pendingVerified(installedVersionCode) }
            if (pending != null) {
                if (installer.canRequestPackageInstalls()) install(s.info)
                else _state.value = UpdateUiState.NeedsInstallPermission(s.info)
            } else {
                startUpdate()
            }
        }
    }

    /** "Later" / closing the dialog: stops a running download (its partial file is deleted). */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = UpdateUiState.Hidden
    }

    /** From [UpdateInstallReceiver]. */
    fun onInstallOutcome(outcome: InstallOutcome) {
        val info = when (val s = _state.value) {
            is UpdateUiState.Installing -> s.info
            is UpdateUiState.ReadyToInstall -> s.info
            is UpdateUiState.Failed -> s.info
            else -> AppUpdateInfo("", "", "", "", 0)
        }
        if (outcome == InstallOutcome.ReinstallRequired) {
            scope.launch(io) { files.clearPending() }
        }
        _state.value = UpdateReducer.afterInstallOutcome(info, outcome)
    }
}
