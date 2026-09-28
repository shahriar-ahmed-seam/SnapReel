package com.snapreel.app

import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapreel.app.util.AppUpdateInfo
import com.snapreel.app.util.update.ApkFacts
import com.snapreel.app.util.update.InstallOutcome
import com.snapreel.app.util.update.InstallStatusMapping
import com.snapreel.app.util.update.InstalledFacts
import com.snapreel.app.util.update.UpdateError
import com.snapreel.app.util.update.UpdateReducer
import com.snapreel.app.util.update.UpdateUiState
import com.snapreel.app.util.update.UpdateValidator
import com.snapreel.app.util.update.Validation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/**
 * Property 9: Bug Condition - Update validation before install.
 *
 * `validate` checks size → parse → package → versionCode → signature, and degrades to
 * `Ok(verified = false)` when the archive's signers can't be read. Every non-Ok result and every
 * installer failure keeps the dialog open with a specific message; a key mismatch (at validation
 * or as `STATUS_FAILURE_CONFLICT`) asks for the one-time reinstall.
 *
 * **Validates: Requirements 2.17, 2.18**
 */
@RunWith(AndroidJUnit4::class)
class UpdateValidationPropertyTest {

    private val keys = listOf("aa01", "bb02", "cc03", "dd04")
    private val signerSet: Arb<Set<String>> = Arb.set(Arb.element(keys), 1..2)

    /** Signers or lineage that may be unreadable (null); no `orNull` (Kotest edge-case crash). */
    private val maybeSet: Arb<Set<String>?> = Arb.choice(signerSet.map { it as Set<String>? }, Arb.constant(null))

    private val apkArb: Arb<ApkFacts?> = Arb.choice(
        Arb.bind(
            Arb.element("com.snapreel.app", "com.snapreel.app", "com.example.other"),
            Arb.long(1L..30L),
            maybeSet,
            maybeSet,
        ) { pkg, vc, signers, lineage -> ApkFacts(pkg, vc, signers, lineage) as ApkFacts? },
        Arb.constant(null),
    )

    private val installedArb: Arb<InstalledFacts> = Arb.bind(
        Arb.long(10L..20L),
        Arb.choice(signerSet, Arb.constant(emptySet())),
    ) { vc, signers -> InstalledFacts("com.snapreel.app", vc, signers) }

    private val sizes: Arb<Pair<Long, Long>> = Arb.choice(
        Arb.long(1L..40_000_000L).map { it to it },
        Arb.bind(Arb.long(0L..40_000_000L), Arb.long(0L..40_000_000L)) { e, a -> e to a },
    )

    /** The expected result, clause by clause (design › `UpdateValidator`). */
    private fun expected(expectedSize: Long, actualSize: Long, apk: ApkFacts?, installed: InstalledFacts): Validation = when {
        actualSize <= 0 || (expectedSize > 0 && actualSize != expectedSize) -> Validation.Incomplete(expectedSize, actualSize)
        apk == null -> Validation.Corrupt
        apk.packageName != installed.packageName -> Validation.WrongPackage(apk.packageName)
        apk.versionCode <= installed.versionCode -> Validation.NotNewer(apk.versionCode, installed.versionCode)
        apk.signers.isNullOrEmpty() || installed.signers.isEmpty() -> Validation.Ok(signatureVerified = false)
        apk.signers == installed.signers -> Validation.Ok(signatureVerified = true)
        installed.signers.size == 1 && installed.signers.single() in apk.lineage.orEmpty() -> Validation.Ok(signatureVerified = true)
        else -> Validation.SignatureMismatch
    }

    @Test
    fun validateAppliesItsChecksInOrder() = runBlocking<Unit> {
        checkAll(1_000, sizes, apkArb, installedArb, Arb.boolean()) { (expectedSize, actualSize), apk, installed, canInstall ->
            val result = UpdateValidator.validate(expectedSize, actualSize, apk, installed)
            assertEquals("validate($expectedSize, $actualSize, $apk, $installed)", expected(expectedSize, actualSize, apk, installed), result)

            val info = AppUpdateInfo("1.4.0", "t", "n", "https://example.invalid/a.apk", expectedSize)
            val next = UpdateReducer.afterValidation(info, result, canInstall)
            when (result) {
                is Validation.Ok -> assertEquals(
                    if (canInstall) UpdateUiState.Installing(info) else UpdateUiState.NeedsInstallPermission(info), next,
                )
                Validation.SignatureMismatch -> assertEquals(UpdateUiState.ReinstallRequired(info), next)
                else -> {
                    assertTrue("a failed check keeps the dialog open with an error: $next", next is UpdateUiState.Failed)
                    assertTrue("the error is specific", (next as UpdateUiState.Failed).error.message.isNotBlank())
                }
            }
        }
    }

    @Test
    fun aRotatedKeyWithTheInstalledSignerInItsLineageMatches() {
        val installed = InstalledFacts("com.snapreel.app", 14, setOf("old"))
        val rotated = ApkFacts("com.snapreel.app", 15, signers = setOf("new"), lineage = setOf("old", "new"))
        assertEquals(Validation.Ok(true), UpdateValidator.validate(10, 10, rotated, installed))
        val unrelated = rotated.copy(lineage = setOf("new"))
        assertEquals(Validation.SignatureMismatch, UpdateValidator.validate(10, 10, unrelated, installed))
    }

    private val statusConstants: Map<String, Int> =
        PackageInstaller::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("STATUS_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    @Test
    fun everyInstallerStatusHasASpecificOutcome() {
        assertTrue("found the STATUS_* constants: $statusConstants", statusConstants.size >= 9)
        for ((name, status) in statusConstants) {
            val outcome = InstallStatusMapping.mapInstallStatus(status, "detail")
            val expected: InstallOutcome = when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.PendingUserAction
                PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed
                PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.ReinstallRequired
                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Failed(UpdateError.Cancelled)
                PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome.Failed(UpdateError.Corrupt)
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.Failed(UpdateError.Incompatible)
                PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.Failed(UpdateError.NoStorage)
                PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.Failed(UpdateError.Blocked)
                else -> InstallOutcome.Failed(UpdateError.Unknown("detail"))
            }
            assertEquals(name, expected, outcome)
            assertEquals("deterministic: $name", outcome, InstallStatusMapping.mapInstallStatus(status, "detail"))

            val info = AppUpdateInfo("1.4.0", "t", "n", "u", 1)
            val next = UpdateReducer.afterInstallOutcome(info, outcome)
            when (outcome) {
                InstallOutcome.ReinstallRequired -> assertEquals("$name asks for the reinstall", UpdateUiState.ReinstallRequired(info), next)
                is InstallOutcome.Failed -> assertTrue(
                    "$name keeps the dialog open with a message",
                    next is UpdateUiState.Failed && next.error.message.isNotBlank(),
                )
                else -> Unit
            }
        }
    }

    @Test
    fun unknownStatusesAreFailuresWithTheSystemMessage() = runBlocking<Unit> {
        val known = statusConstants.values.toSet()
        checkAll(1_000, Arb.int(-1000..1000)) { status ->
            if (status in known) return@checkAll
            assertEquals(InstallOutcome.Failed(UpdateError.Unknown("why")), InstallStatusMapping.mapInstallStatus(status, "why"))
        }
    }
}
