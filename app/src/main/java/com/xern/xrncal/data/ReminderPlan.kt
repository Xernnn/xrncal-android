package com.xern.xrncal.data

import com.xern.xrncal.domain.EventReminder
import com.xern.xrncal.domain.HolidayCalendar
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Reminders
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One reminder to show: which date of which event, and how long before it. */
data class Ring(val occurrence: Occurrence, val minutesBefore: Int, val fireAt: Long) {
    val key: String get() = ringKey(occurrence, minutesBefore)

    companion object {
        fun ringKey(o: Occurrence, minutesBefore: Int) = "${o.resourceId}|${o.start.toEpochMilli()}|$minutesBefore"
    }
}

/** The phone's alarm: one, for the soonest reminder. AlarmManager on the phone, a field in tests. */
interface AlarmScheduler {
    fun set(at: Long)
    fun cancel()
}

/** Which reminders have rung (key -> when), so none rings twice. */
interface RungLog {
    var rung: Map<String, Long>
}

class MemoryRungLog : RungLog {
    override var rung: Map<String, Long> = emptyMap()
}

/** A reminder snoozed: which date of which event, how long before it, and when it rings again. */
data class Snooze(val resourceId: String, val start: Long, val minutesBefore: Int, val at: Long)

/** The snoozed reminders (key -> snooze), kept across a reboot. */
interface SnoozeLog {
    var snoozed: Map<String, Snooze>
}

class MemorySnoozeLog : SnoozeLog {
    override var snoozed: Map<String, Snooze> = emptyMap()
}

/**
 * Keeps the phone's one alarm on the soonest reminder to come (docs/spec.md, "Reminders"), and says
 * what to show when it goes off. Reminders are an event's own VALARMs: one without any never rings,
 * as in One Calendar, and holidays never ring.
 */
class ReminderPlanner(
    private val events: EventRepository,
    private val settings: () -> Settings,
    val log: RungLog,
    private val scheduler: AlarmScheduler,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
    private val snoozes: SnoozeLog = MemorySnoozeLog(),
) {
    private val mutex = Mutex()

    /** An event's own alarms: one with none never rings (the defaults only fill in new events). */
    private fun effective(o: Occurrence): List<EventReminder> = Reminders.effective(o.reminders)

    private fun anchor(o: Occurrence): Long = Reminders.anchor(o.start, o.allDay, o.firstDay, zone()).toEpochMilli()

    private suspend fun upcoming(now: Instant): List<Occurrence> {
        val today = now.atZone(zone()).toLocalDate()
        return events.occurrences(today.minusDays(1), today.plusDays(LOOK_AHEAD_DAYS)).filter { HolidayCalendar.of(it.calendarId) == null }
    }

    /** Sets the alarm for the soonest reminder not yet rung; returns its time, or null for none. */
    suspend fun rearm(): Long? = mutex.withLock {
        val now = clock().toEpochMilli()
        val rung = log.rung
        var next: Long? = null
        for (o in upcoming(clock())) {
            val anchor = anchor(o)
            for (r in effective(o)) {
                if (r.email) continue
                val at = anchor - r.minutesBefore * 60_000L
                if (Ring.ringKey(o, r.minutesBefore) in rung) continue
                // Due already (the phone was off, or it was just made): ring now, while it is still worth it.
                val due = if (at <= now) Reminders.due(anchor, listOf(r), now, GRACE_MS).ring?.let { now } else at
                if (due != null && (next == null || due < next)) next = due
            }
        }
        // A snoozed reminder rings again at its time (or now, if the phone slept through it).
        for (z in snoozes.snoozed.values) {
            val due = maxOf(z.at, now)
            if (next == null || due < next) next = due
        }
        log.rung = rung.filterValues { it > now - KEEP_MS }
        if (next == null) scheduler.cancel() else scheduler.set(next)
        next
    }

    /**
     * A reminder's Snooze (the laptop's 5, 10, 15, 30 minutes or an hour): it rings again
     * [minutes] from now, then the alarm is set for whatever comes first.
     */
    suspend fun snooze(resourceId: String, start: Long, minutesBefore: Int, minutes: Int): Long? {
        mutex.withLock {
            val at = clock().toEpochMilli() + minutes * 60_000L
            snoozes.snoozed = snoozes.snoozed + ("$resourceId|$start|$minutesBefore" to Snooze(resourceId, start, minutesBefore, at))
        }
        return rearm()
    }

    /**
     * The alarm went off: the reminders due now, one per event (of several overdue only the latest),
     * each marked rung with every other due one of its event.
     */
    suspend fun ringing(): List<Ring> = mutex.withLock {
        val now = clock().toEpochMilli()
        val rung = log.rung.toMutableMap()
        val out = mutableListOf<Ring>()
        for (o in upcoming(clock())) {
            val list = effective(o).filter { Ring.ringKey(o, it.minutesBefore) !in rung }
            val due = Reminders.due(anchor(o), list, now, GRACE_MS)
            for (h in due.handled) rung[Ring.ringKey(o, h.minutesBefore)] = h.fireAt
            due.ring?.let { out += Ring(o, it.minutesBefore, it.fireAt) }
        }
        log.rung = rung
        // Snoozed ones now due, for an event still there.
        val (due, later) = snoozes.snoozed.entries.partition { it.value.at <= now }
        snoozes.snoozed = later.associate { it.key to it.value }
        for ((_, z) in due) {
            val o = events.find(z.resourceId, z.start) ?: continue
            if (o.start.toEpochMilli() != z.start) continue
            out += Ring(o, z.minutesBefore, z.at)
        }
        out
    }

    companion object {
        const val LOOK_AHEAD_DAYS = 8L
        /** A reminder at the start itself still shows this long after. */
        const val GRACE_MS = 2 * 60_000L
        const val KEEP_MS = 10 * 86_400_000L
    }
}
