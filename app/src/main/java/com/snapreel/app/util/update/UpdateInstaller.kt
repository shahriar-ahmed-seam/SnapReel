package com.snapreel.app.util.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A specific, user-facing reason an update couldn't be installed. */
sealed interface UpdateError {
    val message: String

    data class Incomplete(val downloaded: Long, val expected: Long) : UpdateError {
        override val message: String
            get() = "The download is incomplete (${mb(downloaded)} of ${mb(expected)} MB). Try again."
    }
    data object Corrupt : UpdateError {
        override val message = "The downloaded file is damaged. Try again."
    }
    data object WrongPackage : UpdateError {
        override val message = "The downloaded file is not a SnapReel update."
    }
    data object NotNewer : UpdateError {
        override val message = "The downloaded version isn't newer than the installed one."
    }
    data object Cancelled : UpdateError {
        override val message = "The installation was cancelled."
    }
    data object NoStorage : UpdateError {
        override val message = "Not enough storage to install the update. Free up some space and try again."
    }
    data object Blocked : UpdateError {
        override val message = "The installation was blocked by the device."
    }
    data object Incompatible : UpdateError {
        override val message = "This update isn't compatible with your device."
    }
    data object LaunchFailed : UpdateError {
        override val message = "Couldn't open the system installer."
    }
    data object NeedsPermission : UpdateError {
        override val message = "Allow 'Install unknown apps' for SnapReel to continue."
    }
    data class DownloadFailed(val detail: String?) : UpdateError {
        override val message = "The download failed. Check your connection and try again."
    }
    data class Unknown(val detail: String?) : UpdateError {
        override val message: String
            get() = if (detail.isNullOrBlank()) "The update couldn't be installed." else "The update couldn't be installed ($detail)."
    }

    private companion object {
        fun mb(bytes: Long) = "%.1f".format(bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
    }
}

/** The result the system installer reports for a session. */
sealed interface InstallOutcome {
    /** The system needs the user to confirm; the receiver starts the confirmation screen. */
    data object PendingUserAction : InstallOutcome
    data object Installed : InstallOutcome
    /** `STATUS_FAILURE_CONFLICT`: signed with a different key, a one-time reinstall is needed. */
    data object ReinstallRequired : InstallOutcome
    data class Failed(val error: UpdateError) : InstallOutcome
}

/** Pure mapping of `PackageInstaller.STATUS_*` to [InstallOutcome]. */
object InstallStatusMapping {
    fun mapInstallStatus(status: Int, message: String?): InstallOutcome = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.PendingUserAction
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.ReinstallRequired
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Failed(UpdateError.Cancelled)
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome.Failed(UpdateError.Corrupt)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.Failed(UpdateError.Incompatible)
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.Failed(UpdateError.NoStorage)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.Failed(UpdateError.Blocked)
        else -> InstallOutcome.Failed(UpdateError.Unknown(message))
    }
}

/** How handing the APK to the system went. */
sealed interface InstallStart {
    /** A session was committed; the outcome arrives through [UpdateInstallReceiver]. */
    data object Committed : InstallStart
    /** The session API failed, so the classic installer screen was opened (no outcome is reported). */
    data object ViewIntentLaunched : InstallStart
    data class Failed(val error: UpdateError) : InstallStart
}

/** The system installer (a seam for coordinator tests). */
interface PackageInstallGateway {
    fun canRequestPackageInstalls(): Boolean
    fun installPermissionIntent(): Intent
    fun install(apk: File): InstallStart
}

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : PackageInstallGateway {

    override fun canRequestPackageInstalls(): Boolean =
        try {
            context.packageManager.canRequestPackageInstalls()
        } catch (_: Exception) {
            false
        }

    override fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Commits a [PackageInstaller] session so the result (including `STATUS_FAILURE_CONFLICT`) is
     * reported back. If the session can't be set up, falls back to the `ACTION_VIEW` installer.
     * A failure to launch anything is reported, never swallowed.
     */
    override fun install(apk: File): InstallStart {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                // Mutable: the system adds the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            InstallStart.Committed
        } catch (_: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            launchViewIntent(apk)
        }
    }

    private fun launchViewIntent(apk: File): InstallStart = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        InstallStart.ViewIntentLaunched
    } catch (_: ActivityNotFoundException) {
        InstallStart.Failed(UpdateError.LaunchFailed)
    } catch (_: Exception) {
        InstallStart.Failed(UpdateError.LaunchFailed)
    }
}

/** Receives the session result from the system installer and hands it to the coordinator. */
class UpdateInstallReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun coordinator(): UpdateCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val coordinator = EntryPointAccessors
            .fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
            .coordinator()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val outcome = InstallStatusMapping.mapInstallStatus(status, message)
        if (outcome == InstallOutcome.PendingUserAction) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            val launched = try {
                if (confirm == null) {
                    false
                } else {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
            } catch (_: Exception) {
                false
            }
            coordinator.onInstallOutcome(if (launched) outcome else InstallOutcome.Failed(UpdateError.LaunchFailed))
        } else {
            coordinator.onInstallOutcome(outcome)
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.snapreel.app.action.UPDATE_INSTALL_STATUS"
    }
}
