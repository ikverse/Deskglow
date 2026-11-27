package com.ikverse.deskglow.update

import android.content.pm.PackageInstaller

/** How Android's installer ended an install the updater started, other than by succeeding (which replaces the app). */
sealed interface InstallOutcome {
    /** The user backed out. Nothing is wrong; the update stays on offer. */
    data object Cancelled : InstallOutcome

    /** Android, or Google Play Protect, refused. [message] says what to do about it. */
    data class Blocked(val message: String) : InstallOutcome

    /** Anything else that went wrong. [message] is shown as it is. */
    data class Failed(val message: String) : InstallOutcome

    companion object {
        const val BLOCKED_MESSAGE =
            "Android blocked this update, most likely Google Play Protect, which does not recognise the file yet. " +
                "Open its message, tap More details, then Install anyway. Or allow Play Protect to scan the app, and try again."

        /** Maps a `PackageInstaller` status, and the text Android gave with it, to what the screen should say. */
        fun fromStatus(status: Int, detail: String?): InstallOutcome = when {
            // A Play Protect refusal arrives as a plain failure whose text names the verification, not as STATUS_FAILURE_BLOCKED.
            detail?.contains("VERIFICATION", ignoreCase = true) == true -> Blocked(BLOCKED_MESSAGE)
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> Blocked(BLOCKED_MESSAGE)
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> Cancelled
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> Failed("There is not enough free storage on this phone to install the update.")
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Failed("This update cannot run on this phone.")
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Failed("This update conflicts with the app already installed. It may have been signed with a different key.")
            status == PackageInstaller.STATUS_FAILURE_INVALID -> Failed("Android says the downloaded file is damaged. Try the download again.")
            else -> Failed(detail?.takeIf(String::isNotBlank)?.let { "Android could not install the update: $it" } ?: "Android could not install the update.")
        }
    }
}
