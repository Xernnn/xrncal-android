package com.xern.xrncal.work

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.xern.xrncal.MainActivity
import com.xern.xrncal.R
import com.xern.xrncal.XrnCalApp
import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.Ring
import com.xern.xrncal.data.RungLog
import com.xern.xrncal.ui.Format
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The one exact alarm, on the soonest reminder. The app declares USE_EXACT_ALARM, which Android 13
 * and later grant at install; SCHEDULE_EXACT_ALARM covers Android 12. Should exact alarms still be
 * refused, the alarm is set inexact.
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val exact: Boolean get() = alarms.canScheduleExactAlarms()

    override fun set(at: Long) {
        val pending = ring(context)
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    override fun cancel() = alarms.cancel(ring(context))

    companion object {
        const val RING = "com.xern.xrncal.RING"

        fun ring(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The rung reminders in prefs. */
class PrefsRungLog(private val prefs: SharedPreferences) : RungLog {
    override var rung: Map<String, Long>
        get() = runCatching {
            val o = JSONObject(prefs.getString("rung", "{}")!!)
            o.keys().asSequence().associateWith { o.getLong(it) }
        }.getOrDefault(emptyMap())
        set(v) = prefs.edit().putString("rung", JSONObject(v as Map<*, *>).toString()).apply()
}

/** The notifications: channel "Reminders". */
object ReminderNotes {
    const val CHANNEL = "reminders"

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, ring: Ring, h24: Boolean) {
        if (!allowed(context)) return
        channel(context)
        val o = ring.occurrence
        val open = PendingIntent.getActivity(
            context,
            ring.key.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.OPEN, o.resourceId)
                .putExtra(MainActivity.START, o.start.toEpochMilli())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = if (o.allDay) Format.dayShort(o.firstDay) else Format.span(o, h24)
        val text = listOf(time, o.location).filter { it.isNotBlank() }.joinToString(" · ")
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(o.title.ifBlank { "(no title)" })
            .setContentText(text)
            .setWhen(o.start.toEpochMilli())
            .setShowWhen(!o.allDay)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(o.resourceId + "|" + o.start.toEpochMilli(), 1, n)
    }
}

/**
 * The reminder alarm, and the moments the alarm must be set again: a reboot, an update of the app,
 * a change of the time or the zone.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as XrnCalApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                when (intent.action) {
                    AndroidAlarmScheduler.RING -> {
                        // An event moved or deleted on the laptop shouldn't ring: sync first, briefly.
                        withTimeoutOrNull(8_000) { graph.syncNow() }
                        val h24 = com.xern.xrncal.ui.Format.is24(context, graph.settings.value.clock)
                        graph.reminders.ringing().forEach { ReminderNotes.show(context, it, h24) }
                        graph.reminders.rearm()
                    }
                    else -> {
                        graph.reminders.rearm() // boot, update, time, zone
                        Widgets.refresh(context)
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED && graph.account.connected) SyncWork.now(context)
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}
