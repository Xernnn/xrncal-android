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

/** The snoozed reminders in prefs, as JSON. */
class PrefsSnoozeLog(private val prefs: SharedPreferences) : com.xern.xrncal.data.SnoozeLog {
    override var snoozed: Map<String, com.xern.xrncal.data.Snooze>
        get() = runCatching {
            val o = JSONObject(prefs.getString("snoozed", "{}")!!)
            o.keys().asSequence().associateWith { k ->
                val z = o.getJSONObject(k)
                com.xern.xrncal.data.Snooze(z.getString("id"), z.getLong("start"), z.getInt("before"), z.getLong("at"))
            }
        }.getOrDefault(emptyMap())
        set(v) {
            val o = JSONObject()
            for ((k, z) in v) o.put(k, JSONObject().put("id", z.resourceId).put("start", z.start).put("before", z.minutesBefore).put("at", z.at))
            prefs.edit().putString("snoozed", o.toString()).apply()
        }
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

    const val SNOOZE = "com.xern.xrncal.SNOOZE"

    fun tag(o: com.xern.xrncal.domain.Occurrence) = o.resourceId + "|" + o.start.toEpochMilli()

    /**
     * A reminder's notification: the title, the time and the place, with Open and Snooze (by
     * Options' snooze length). With "Notification data sensitive" on, the lock screen shows only
     * that a reminder is there.
     */
    fun show(context: Context, ring: Ring, h24: Boolean, snoozeMinutes: Int = 10, sensitive: Boolean = false) {
        if (!allowed(context)) return
        channel(context)
        val o = ring.occurrence
        val snooze = PendingIntent.getBroadcast(
            context,
            ring.key.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(SNOOZE)
                .putExtra("id", o.resourceId).putExtra("start", o.start.toEpochMilli()).putExtra("before", ring.minutesBefore).putExtra("minutes", snoozeMinutes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .addAction(Notification.Action.Builder(null, "Snooze " + com.xern.xrncal.domain.Reminders.choiceLabel(snoozeMinutes), snooze).build())
            .setVisibility(if (sensitive) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .apply {
                if (sensitive) setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Reminder").build())
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(tag(o), 1, n)
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
                        val s = graph.settings.value
                        val h24 = com.xern.xrncal.ui.Format.is24(context, s.clock)
                        graph.reminders.ringing().forEach { ReminderNotes.show(context, it, h24, s.snoozeMinutes, s.privateNotifications) }
                        graph.reminders.rearm()
                    }
                    ReminderNotes.SNOOZE -> {
                        val id = intent.getStringExtra("id") ?: return@launch
                        val start = intent.getLongExtra("start", 0)
                        context.getSystemService(NotificationManager::class.java).cancel("$id|$start", 1)
                        graph.reminders.snooze(id, start, intent.getIntExtra("before", 0), intent.getIntExtra("minutes", graph.settings.value.snoozeMinutes))
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
