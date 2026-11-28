package com.ikverse.deskglow.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ikverse.deskglow.graph
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The updater's pieces that need Android: the package manager for the keys, and the intents that open the installer. */

class AndroidApkInspector(private val context: Context) : ApkInspector {
    private val pm: PackageManager get() = context.packageManager

    override fun installed(): ApkFacts = try {
        facts(pm.getPackageInfo(context.packageName, KEYS))
    } catch (_: PackageManager.NameNotFoundException) {
        // Cannot happen for the running app; an empty set of keys makes every file fail the check.
        ApkFacts(context.packageName, 0L, emptySet())
    }

    override fun inspect(file: File): ApkFacts? = try {
        pm.getPackageArchiveInfo(file.path, KEYS)?.let(::facts)
    } catch (_: Exception) {
        null
    }

    /** The keys from the newer signing info, or else from the older list of signatures (all a downloaded file gives before Android 13). */
    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo): ApkFacts {
        val keys = info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures.orEmpty()
        return ApkFacts(info.packageName, info.longVersionCode, keys.map { sha256(it.toByteArray()) }.toSet())
    }

    private companion object {
        /**
         * Android 9 to 12 read a downloaded file's keys only when asked with the older flag, leaving its signing info
         * empty when asked with the newer one alone; so both are asked for.
         */
        @Suppress("DEPRECATION")
        const val KEYS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class AndroidInstallLauncher(private val context: Context) : InstallLauncher {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Gives [file] to Android's package installer as an install session, rather than opening it in whatever app handles
     * APK files. Android then reports how the install ended, to [InstallResultReceiver], so a refusal is not silent.
     */
    override fun launch(file: File) {
        val installer = context.packageManager.packageInstaller
        // Sessions left behind by earlier attempts that were never finished.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("deskglow.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(resultSender())
            }
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            throw UpdateException("The update file could not be handed to Android's installer.")
        } catch (e: SecurityException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            throw UpdateException("Android would not let Deskglow start the install.")
        }
    }

    /**
     * Where Android sends the outcome. Mutable, because Android adds the status to it; the flag only exists from Android 12,
     * and older phones (the Note 9 runs Android 10) have no immutable default to opt out of.
     */
    private fun resultSender(): IntentSender = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    ).intentSender

    companion object {
        /** The file provider named in the manifest, which lets Android's installer read the downloaded file. */
        fun authority(context: Context) = "${context.packageName}.updates"

        /** Where downloads go: the app's cache, so Android can reclaim it and nothing else can read it. */
        fun downloadDir(context: Context) = File(context.cacheDir, "updates")
    }
}

/**
 * Hears from Android's installer. When it needs the user's say, it opens the confirmation screen; when it refuses, it tells
 * the updater, which shows why. A successful install replaces the app, so there is nothing to do for it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    context.graph.updates.report(InstallOutcome.Failed("This phone has no installer screen that can confirm the update."))
                }
            }
            else -> context.graph.updates.report(InstallOutcome.fromStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}
