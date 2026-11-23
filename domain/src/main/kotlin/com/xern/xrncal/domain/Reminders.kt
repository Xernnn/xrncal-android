package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Event reminders (the laptop app's reminders.ts). A reminder is an offset before the event starts.
 * An all-day event starts at local midnight on its first day, so 900 minutes is "the day before at
 * 09:00", as iCalendar counts it. An offset is never negative.
 */
data class EventReminder(val minutesBefore: Int, val email: Boolean = false)

object Reminders {
    /** Four weeks. */
    const val MAX_MINUTES = 40320

    val TIMED_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

    /** The day before at 09:00 and at 18:00, two and three days before at 09:00, a week before at 09:00. */
    val ALL_DAY_PRESETS = listOf(900, 360, 2340, 3780, 9540)

    /** Whole minutes in range, duplicates dropped, soonest to the start first. */
    fun normalize(list: List<EventReminder>): List<EventReminder> {
        val seen = HashSet<String>()
        val out = mutableListOf<EventReminder>()
        for (r in list) {
            if (r.minutesBefore < 0) continue
            val m = minOf(r.minutesBefore, MAX_MINUTES)
            val key = "${if (r.email) "email" else "display"}:$m"
            if (!seen.add(key)) continue
            out += EventReminder(m, r.email)
        }
        return out.sortedWith(compareBy<EventReminder> { it.minutesBefore }.thenBy { if (it.email) 1 else 0 })
    }

    fun normalizeMinutes(minutes: List<Double>, email: Boolean = false): List<EventReminder> =
        normalize(minutes.filter { it.isFinite() }.map { EventReminder(it.roundToInt(), email) })

    /** Minutes before a timed event and before an all-day event's day; null for none. */
    data class Defaults(val timed: Int?, val allDay: Int?)

    /**
     * What rings for an event: its own list wins, an empty one included; without one (no VALARM at
     * all) a timed event takes [defaults].timed and an all-day one [defaults].allDay.
     */
    fun effective(eventReminders: List<EventReminder>?, allDay: Boolean, calendarDefault: List<EventReminder>?, defaults: Defaults): List<EventReminder> {
        if (eventReminders != null) return eventReminders
        if (allDay) return if (defaults.allDay == null) emptyList() else listOf(EventReminder(defaults.allDay))
        if (calendarDefault != null) return calendarDefault
        return if (defaults.timed == null) emptyList() else listOf(EventReminder(defaults.timed))
    }

    /** The instant reminders count back from: a timed event's start, or midnight in [zone] on an all-day event's date. */
    fun anchor(start: Instant, allDay: Boolean, date: LocalDate, zone: ZoneId): Instant =
        if (!allDay) start else date.atStartOfDay(zone).toInstant()

    data class Due(val minutesBefore: Int, val fireAt: Long)

    data class DueResult(val ring: Due?, val handled: List<Due>)

    /**
     * Which display reminders of an occurrence should ring at [now]. One stays due from its time
     * until the occurrence starts (or [graceMs] past its time, for one at the start itself), so a
     * phone that slept through 08:50 still says something at 08:55 about 09:00. Of several overdue
     * together only the latest rings, but all are handled, so none rings later.
     */
    fun due(anchorMs: Long, reminders: List<EventReminder>, now: Long, graceMs: Long): DueResult {
        val handled = mutableListOf<Due>()
        for (r in reminders) {
            if (r.email) continue
            val fireAt = anchorMs - r.minutesBefore * 60_000L
            if (fireAt <= now && now < maxOf(anchorMs, fireAt + graceMs)) handled += Due(r.minutesBefore, fireAt)
        }
        return DueResult(handled.maxByOrNull { it.fireAt }, handled)
    }

    sealed interface When {
        data object Now : When
        data class InMinutes(val minutes: Int) : When
        data class TodayAt(val start: ZonedDateTime) : When
        data class TomorrowAt(val start: ZonedDateTime) : When
        data class OnDateAt(val start: ZonedDateTime) : When
        data object Today : When
        data object Tomorrow : When
        data class OnDate(val date: LocalDate) : When
    }

    /** How far off an occurrence is, in the words a reminder uses for it. */
    fun describeWhen(start: Instant, allDay: Boolean, date: LocalDate, now: Instant, zone: ZoneId): When {
        val today = now.atZone(zone).toLocalDate()
        if (allDay) {
            val days = ChronoUnit.DAYS.between(today, date)
            return when {
                days <= 0 -> When.Today
                days == 1L -> When.Tomorrow
                else -> When.OnDate(date)
            }
        }
        val minutes = ceil((start.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()
        if (minutes <= 0) return When.Now
        if (minutes < 60) return When.InMinutes(minutes)
        val zoned = start.atZone(zone)
        val days = ChronoUnit.DAYS.between(today, zoned.toLocalDate())
        return when (days) {
            0L -> When.TodayAt(zoned)
            1L -> When.TomorrowAt(zoned)
            else -> When.OnDateAt(zoned)
        }
    }

    sealed interface Offset {
        data object AtStart : Offset
        data class Minutes(val count: Int) : Offset
        data class Hours(val count: Int) : Offset
        data class Days(val count: Int) : Offset
        data class Weeks(val count: Int) : Offset
        /** All-day: on the day, at [time] ("HH:mm"). */
        data class OnDayAt(val time: String) : Offset
        /** All-day: [days] days before, at [time] ("HH:mm"). */
        data class DaysBeforeAt(val days: Int, val time: String) : Offset
    }

    fun describeOffset(minutesBefore: Int, allDay: Boolean): Offset {
        val m = maxOf(0, minutesBefore)
        if (allDay) {
            if (m == 0) return Offset.OnDayAt("00:00")
            val days = ceil(m / 1440.0).toInt()
            val clock = days * 1440 - m
            return Offset.DaysBeforeAt(days, "%02d:%02d".format(clock / 60, clock % 60))
        }
        return when {
            m == 0 -> Offset.AtStart
            m % 10080 == 0 -> Offset.Weeks(m / 10080)
            m % 1440 == 0 -> Offset.Days(m / 1440)
            m % 60 == 0 -> Offset.Hours(m / 60)
            else -> Offset.Minutes(m)
        }
    }

    /** A short label: "At start", "10 min", "2 h", "1 day", "1 week", "1 day before, 09:00". */
    fun label(minutesBefore: Int, allDay: Boolean): String = when (val o = describeOffset(minutesBefore, allDay)) {
        Offset.AtStart -> "At start"
        is Offset.Minutes -> "${o.count} min"
        is Offset.Hours -> "${o.count} h"
        is Offset.Days -> if (o.count == 1) "1 day" else "${o.count} days"
        is Offset.Weeks -> if (o.count == 1) "1 week" else "${o.count} weeks"
        is Offset.OnDayAt -> "On the day, ${o.time}"
        is Offset.DaysBeforeAt -> "${if (o.days == 1) "1 day" else "${o.days} days"} before, ${o.time}"
    }

    /** What CalDAV holds of a list, as written here: the display reminders. */
    fun forCalDav(list: List<EventReminder>): List<EventReminder> = normalize(list).filter { !it.email }
}
