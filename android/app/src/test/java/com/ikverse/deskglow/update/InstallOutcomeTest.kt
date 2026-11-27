package com.ikverse.deskglow.update

import android.content.pm.PackageInstaller
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallOutcomeTest {
    @Test
    fun `a refusal from the package verifier is blocked, however Android words the status`() {
        val outcome = InstallOutcome.fromStatus(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed")
        assertEquals(InstallOutcome.Blocked(InstallOutcome.BLOCKED_MESSAGE), outcome)
    }

    @Test
    fun `Android's own blocked status is blocked`() {
        assertIs<InstallOutcome.Blocked>(InstallOutcome.fromStatus(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
    }

    @Test
    fun `backing out is a cancel, not an error`() {
        assertEquals(InstallOutcome.Cancelled, InstallOutcome.fromStatus(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED: User rejected permissions"))
    }

    @Test
    fun `storage, compatibility, conflict and damage each say what is wrong`() {
        val messages = listOf(
            PackageInstaller.STATUS_FAILURE_STORAGE to "storage",
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to "cannot run",
            PackageInstaller.STATUS_FAILURE_CONFLICT to "different key",
            PackageInstaller.STATUS_FAILURE_INVALID to "damaged",
        ).map { (status, word) -> InstallOutcome.fromStatus(status, null).also { assertIs<InstallOutcome.Failed>(it) } to word }
        for ((outcome, word) in messages) assertTrue(word in (outcome as InstallOutcome.Failed).message, "$word in ${outcome.message}")
    }

    @Test
    fun `an unknown failure passes Android's own words through`() {
        assertEquals(
            InstallOutcome.Failed("Android could not install the update: INSTALL_FAILED_OTHER"),
            InstallOutcome.fromStatus(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_OTHER"),
        )
        assertEquals(InstallOutcome.Failed("Android could not install the update."), InstallOutcome.fromStatus(PackageInstaller.STATUS_FAILURE, " "))
    }
}
