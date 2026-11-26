package com.xern.xrncal.work

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.xern.xrncal.R

/**
 * HyperOS's Autostart switch, as xrnrule reads it (its OemAutostart). HyperOS turns it off at every
 * install, and then nothing brings xrncal back after a reboot or a kill: no alarm is set again, so
 * reminders don't ring, the widgets' midnight redraw can't start the app, and WorkManager's sync
 * doesn't run. Only Xiaomi's can be read: an app op of its own (10008), which Android's public API
 * has no name for. The pages are explicit components, so no `<queries>` entry is needed.
 */
object OemAutostart {
    fun isKnownOem(): Boolean = candidates(Build.MANUFACTURER.lowercase()).isNotEmpty()

    /** Whether autostart is on, or null where the phone won't say. */
    fun enabled(context: Context): Boolean? {
        if ("xiaomi" !in Build.MANUFACTURER.lowercase()) return null
        return runCatching {
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
            opAllows(check.invoke(context.getSystemService(AppOpsManager::class.java), MIUI_AUTOSTART_OP, Process.myUid(), context.packageName) as? Int)
        }.getOrNull()
    }

    /** An app op's mode as on or off; null for one that leaves it to something else. */
    fun opAllows(mode: Int?): Boolean? = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
        else -> null
    }

    /** The page where autostart is turned on: the maker's own, else the app's details. */
    fun open(context: Context) {
        for (candidate in candidates(Build.MANUFACTURER.lowercase())) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // The next known page for this maker.
            } catch (_: SecurityException) {
                // Not exported on this build.
            }
        }
        context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * "Autostart off" while it is, as an ongoing notification whose tap opens the switch; gone once
     * it is back on. Run whenever the app's process starts and when it comes to the front.
     */
    fun check(context: Context) {
        val notes = context.getSystemService(NotificationManager::class.java)
        if (enabled(context) != false) {
            notes.cancel(NOTE)
            return
        }
        if (!ReminderNotes.allowed(context)) return
        notes.createNotificationChannel(NotificationChannel(CHANNEL, "Autostart", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, NOTE, Intent(context, AutostartActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Autostart off")
            .setContentText("Turn on")
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notes.notify(NOTE, n)
    }

    private fun candidates(manufacturer: String): List<Intent> = when {
        "xiaomi" in manufacturer -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.permcenter.MainActivity"),
        )
        "oppo" in manufacturer -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo" in manufacturer -> listOf(component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        "huawei" in manufacturer -> listOf(component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        "oneplus" in manufacturer -> listOf(component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
        else -> emptyList()
    }

    private fun component(pkg: String, cls: String) = Intent().apply { component = ComponentName(pkg, cls) }

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private const val MIUI_AUTOSTART_OP = 10008
    private const val CHANNEL = "autostart"
    private const val NOTE = 7
}

/**
 * The battery-optimisation exemption, as xrnimg asks for it: optimised, Android holds the background
 * sync's jobs back when the app is seldom opened.
 */
object BatteryExemption {
    fun exempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own question, or the list of apps and their battery use where it can't be asked. */
    @SuppressLint("BatteryLife") // installed by hand, never through Play, whose policy this check is
    fun ask(context: Context) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        for (intent in listOf(ask, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), OemAutostart.appDetails(context))) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // The next page.
            }
        }
    }
}

/** A notification can only name one page; this tries each known page in turn ([OemAutostart.open]). */
class AutostartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { OemAutostart.open(this) }
        finish()
    }
}
