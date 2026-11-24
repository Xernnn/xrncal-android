package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Zones
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.dmfs.rfc5545.recur.RecurrenceRule

/** A new event's draft, as `+` and the editor start one. */
object NewEvents {
    /** The next whole hour on today (noon on another day, or [hour] on [day]), as long as Settings say. */
    fun draft(day: LocalDate, hour: Int?, now: Instant, zone: ZoneId, minutes: Int, reminders: List<EventReminder>? = null): EventDraft {
        val local = now.atZone(zone)
        val startTime = when {
            hour != null -> LocalTime.of(hour, 0)
            day == local.toLocalDate() -> local.toLocalTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
            else -> LocalTime.of(12, 0)
        }
        val start = if (hour == null && day == local.toLocalDate() && startTime == LocalTime.MIDNIGHT) day.plusDays(1).atTime(0, 0) else day.atTime(startTime)
        val end = start.plusMinutes(minutes.toLong())
        return EventDraft("", false, start.toLocalDate(), start.toLocalTime(), end.toLocalDate(), end.toLocalTime(), zone, reminders = reminders)
    }
}

/**
 * Another app's "add to calendar" (docs/spec.md, "Other apps"): Android's insert intent for
 * `CalendarContract.Events`, whose extras say what they know of the event. Read into a new event's
 * draft for the editor; nothing is saved until ✓.
 */
object InsertRequest {
    // CalendarContract's names: Events.TITLE, DESCRIPTION, EVENT_LOCATION, RRULE, EVENT_TIMEZONE,
    // EXTRA_EVENT_BEGIN_TIME, EXTRA_EVENT_END_TIME, EXTRA_EVENT_ALL_DAY; and Intent.EXTRA_EMAIL.
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val LOCATION = "eventLocation"
    const val RRULE = "rrule"
    const val TIME_ZONE = "eventTimezone"
    const val BEGIN = "beginTime"
    const val END = "endTime"
    const val ALL_DAY = "allDay"
    const val EMAIL = "android.intent.extra.EMAIL"

    private const val DAY_MS = 86_400_000L

    /**
     * The draft [extras] describe, in [zone] unless they name the event's own. Times left out are a
     * new event's (the next hour, [minutes] long); an all-day event covers the days its times fall
     * on. Guests go into the notes, since the editor has no guests field.
     */
    fun draft(extras: Map<String, Any?>, now: Instant, zone: ZoneId, minutes: Int, defaults: Reminders.Defaults): EventDraft {
        val begin = millis(extras[BEGIN])
        val end = millis(extras[END])
        val allDay = flag(extras[ALL_DAY])
        val eventZone = text(extras[TIME_ZONE]).takeIf { it.isNotBlank() }?.let(Zones::ianaFor) ?: zone
        val notes = withGuests(text(extras[DESCRIPTION]), emails(extras[EMAIL]))
        val base = EventDraft(
            title = text(extras[TITLE]).trim(), allDay = allDay,
            startDate = LocalDate.MIN, startTime = LocalTime.MIDNIGHT, endDate = LocalDate.MIN, endTime = LocalTime.MIDNIGHT,
            zone = eventZone, rrule = readableRule(text(extras[RRULE])), reminders = defaults.forNew(allDay),
            location = text(extras[LOCATION]).trim(), notes = notes,
        )
        if (allDay) {
            val first = begin?.let { dayOf(it, zone) } ?: now.atZone(zone).toLocalDate()
            val last = end?.let { lastDayOf(it, zone) }?.takeIf { !it.isBefore(first) } ?: first
            return base.copy(startDate = first, endDate = last, zone = zone)
        }
        val fresh = NewEvents.draft(now.atZone(zone).toLocalDate(), null, now, zone, minutes)
        val start = begin?.let { Instant.ofEpochMilli(it) } ?: Zones.instantOf(fresh.start, zone.rules)
        val stop = end?.let { Instant.ofEpochMilli(it) }?.takeIf { !it.isBefore(start) } ?: start.plus(minutes.toLong(), ChronoUnit.MINUTES)
        val s = start.atZone(eventZone).toLocalDateTime()
        val e = stop.atZone(eventZone).toLocalDateTime()
        return base.copy(startDate = s.toLocalDate(), startTime = s.toLocalTime().withNano(0), endDate = e.toLocalDate(), endTime = e.toLocalTime().withNano(0))
    }

    /** [notes], then a "Guests:" line naming [guests], when there are any. */
    fun withGuests(notes: String, guests: List<String>): String =
        listOf(notes.trim(), if (guests.isEmpty()) "" else "Guests: " + guests.joinToString(", ")).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Intent.EXTRA_EMAIL as apps send it: a comma-separated string, or an array or list of addresses. */
    fun emails(value: Any?): List<String> {
        val parts = when (value) {
            is String -> value.split(',', ';', '\n')
            is Array<*> -> value.map { it?.toString().orEmpty() }
            is Iterable<*> -> value.map { it?.toString().orEmpty() }
            else -> emptyList()
        }
        return parts.map { it.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * An all-day event's day from a time: Android writes them at midnight UTC; an app that sends
     * its own midnight (or any other time) means the day it falls on here.
     */
    private fun dayOf(ms: Long, zone: ZoneId): LocalDate =
        if (Math.floorMod(ms, DAY_MS) == 0L) Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() else Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** The last day an all-day event covers, from its end: a midnight ends the day before it. */
    private fun lastDayOf(ms: Long, zone: ZoneId): LocalDate {
        if (Math.floorMod(ms, DAY_MS) == 0L) return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        val local = Instant.ofEpochMilli(ms).atZone(zone)
        return if (local.toLocalTime() == LocalTime.MIDNIGHT) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** A time in milliseconds, as a long, an int or digits; null when missing or not a time. */
    private fun millis(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }?.takeIf { it > 0 }

    private fun flag(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Int -> value != 0
        is String -> value.trim().equals("true", true) || value.trim() == "1"
        else -> false
    }

    private fun text(value: Any?): String = (value as? CharSequence)?.toString().orEmpty()

    /** An RRULE value lib-recur can read, without its "RRULE:" name; null for none or one it can't. */
    internal fun readableRule(value: String?): String? {
        val r = value?.trim()?.removePrefix("RRULE:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return r.takeIf { Regex("""(^|;)FREQ=""", RegexOption.IGNORE_CASE).containsMatchIn(it) && runCatching { RecurrenceRule(it) }.isSuccess }
    }
}
