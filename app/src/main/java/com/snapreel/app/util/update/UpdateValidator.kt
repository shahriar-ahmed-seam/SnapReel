package com.snapreel.app.util.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.snapreel.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a downloaded APK says about itself. [signers] is null when the platform couldn't report
 * them (then the signature check is skipped); [lineage] is the key-rotation history, if any.
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>?,
    val lineage: Set<String>?,
)

/** The installed app. [signers] is empty when they couldn't be read. */
data class InstalledFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
)

sealed interface Validation {
    /** Safe to hand to the installer. [signatureVerified] is false when the signers couldn't be read. */
    data class Ok(val signatureVerified: Boolean) : Validation
    data class Incomplete(val expected: Long, val actual: Long) : Validation
    data object Corrupt : Validation
    data class WrongPackage(val found: String) : Validation
    data class NotNewer(val apk: Long, val installed: Long) : Validation
    /** Signed with a different key: Android would refuse the update ("App not installed"). */
    data object SignatureMismatch : Validation
}

/** Pure pre-install checks, in order: size → parse → package → versionCode → signature. */
object UpdateValidator {
    fun validate(expectedSize: Long, actualSize: Long, apk: ApkFacts?, installed: InstalledFacts): Validation {
        if (actualSize <= 0 || (expectedSize > 0 && actualSize != expectedSize)) {
            return Validation.Incomplete(expectedSize, actualSize)
        }
        if (apk == null) return Validation.Corrupt
        if (apk.packageName != installed.packageName) return Validation.WrongPackage(apk.packageName)
        if (apk.versionCode <= installed.versionCode) return Validation.NotNewer(apk.versionCode, installed.versionCode)
        val signers = apk.signers?.takeIf { it.isNotEmpty() } ?: return Validation.Ok(signatureVerified = false)
        if (installed.signers.isEmpty()) return Validation.Ok(signatureVerified = false)
        val matches = signers == installed.signers ||
            (installed.signers.size == 1 && installed.signers.single() in (apk.lineage ?: emptySet()))
        return if (matches) Validation.Ok(signatureVerified = true) else Validation.SignatureMismatch
    }
}

/** Reads [ApkFacts] and [InstalledFacts] (a seam for coordinator tests). */
interface ApkFactsSource {
    /** Null when the file isn't a readable APK. Never throws. */
    fun inspect(apk: File): ApkFacts?
    fun installed(): InstalledFacts
}

/**
 * The Android side of validation. Signing info differs by API level (`GET_SIGNING_CERTIFICATES`
 * on 28+, `GET_SIGNATURES` on 26–27), and some versions return an archive without it; missing or
 * unreadable signing info becomes `signers = null` instead of a crash or a block.
 */
@Singleton
class ApkInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkFactsSource {

    private val pm: PackageManager get() = context.packageManager

    @Suppress("DEPRECATION")
    private val signingFlags: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    override fun inspect(apk: File): ApkFacts? = try {
        val info = archiveInfo(apk.absolutePath)
        info?.let {
            val (signers, lineage) = signersOf(it)
            ApkFacts(it.packageName, PackageInfoCompat.getLongVersionCode(it), signers, lineage)
        }
    } catch (_: Exception) {
        null
    }

    override fun installed(): InstalledFacts {
        val signers = try {
            signersOf(installedInfo()).first ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        return InstalledFacts(context.packageName, BuildConfig.VERSION_CODE.toLong(), signers)
    }

    @SuppressLint("WrongConstant")
    private fun archiveInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signingFlags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, signingFlags)
        }

    private fun installedInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(signingFlags.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, signingFlags)
        }

    /** (current signers, lineage) as SHA-256 hex digests; (null, null) when unavailable. */
    private fun signersOf(info: PackageInfo): Pair<Set<String>?, Set<String>?> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            when {
                signing == null -> null to null
                signing.hasMultipleSigners() -> signing.apkContentsSigners.digests() to null
                else -> {
                    val history = signing.signingCertificateHistory?.toList().orEmpty()
                    val current = history.lastOrNull()
                    (current?.let { setOf(digest(it)) }) to history.map(::digest).toSet().ifEmpty { null }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.digests() to null
        }
    } catch (_: Exception) {
        null to null
    }

    private fun Array<Signature>?.digests(): Set<String>? =
        this?.map(::digest)?.toSet()?.ifEmpty { null }

    private fun digest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
