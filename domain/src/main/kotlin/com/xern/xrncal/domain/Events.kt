package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Component
import com.xern.xrncal.domain.ical.ICalParser
import com.xern.xrncal.domain.ical.ICalTime
import com.xern.xrncal.domain.ical.Zones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

/** An iCalendar DURATION (`P1D`, `-PT15M`, `P1W`): nominal days and exact seconds. */
data class IcalDuration(val days: Long, val seconds: Long) {
    val negative: Boolean get() = days < 0 || seconds < 0
    val totalMinutes: Long get() = days * 1440 + seconds / 60

    companion object {
        private val PATTERN = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

        fun parse(text: String?): IcalDuration? {
            val m = PATTERN.matchEntire(text?.trim()?.uppercase() ?: return null) ?: return null
            if (m.groupValues.drop(2).all { it.isEmpty() }) return null
            val sign = if (m.groupValues[1] == "-") -1 else 1
            fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0
            val days = n(2) * 7 + n(3)
            val seconds = n(4) * 3600 + n(5) * 60 + n(6)
            return IcalDuration(sign * days, sign * seconds)
        }

        /** `-P1DT15H` for 2340 minutes before; `PT0S` for the start itself. */
        fun before(minutes: Int): String {
            if (minutes <= 0) return "PT0S"
            val d = minutes / 1440
            val h = minutes % 1440 / 60
            val m = minutes % 60
            val time = (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "")
            return "-P" + (if (d > 0) "${d}D" else "") + (if (time.isNotEmpty()) "T$time" else "")
        }
    }
}

/** One VEVENT, read for showing and expanding. The component itself stays as it was. */
class EventView(val component: Component, val zones: Zones) {
    /** The UID as written: it is the event's identity everywhere, so it is never unescaped or trimmed further. */
    val uid: String = component.prop("UID")?.value?.trim().orEmpty()
    val title: String get() = component.text("SUMMARY").orEmpty()
    val location: String get() = component.text("LOCATION").orEmpty()
    val notes: String get() = component.text("DESCRIPTION").orEmpty()
    val start: ICalTime? = ICalTime.of(component.prop("DTSTART"))
    val allDay: Boolean = start?.isDate == true
    val rrule: String? = component.prop("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() }
    val recurrenceId: ICalTime? = ICalTime.of(component.prop("RECURRENCE-ID"))
    val exdates: List<ICalTime> by lazy { component.props("EXDATE").flatMap(ICalTime::list) }
    val rdates: List<ICalTime> by lazy { component.props("RDATE").flatMap(ICalTime::list) }
    val cancelled: Boolean get() = component.prop("STATUS")?.value?.trim().equals("CANCELLED", true)
    val isSeries: Boolean get() = rrule != null || rdates.isNotEmpty()

    /** RFC 7986 COLOR, when it is a hex colour this app can draw (CSS names from other clients are left). */
    val color: Int? get() = component.prop("COLOR")?.value?.let(::hexColor)

    /** The zone a timed start is read in; UTC for `...Z`, the phone's for a floating time. */
    val rules: ZoneRules = when {
        start == null || start.isDate -> zones.floating.rules
        start.utc -> ZoneOffset.UTC.rules
        else -> zones.rules(start.tzid)
    }

    val startInstant: Instant? = start?.instant(zones)

    /** How long each date lasts: DTEND - DTSTART exactly, or DURATION, or nothing for a time alone. */
    val length: Duration by lazy {
        val s = start ?: return@lazy Duration.ZERO
        val endProp = ICalTime.of(component.prop("DTEND"))
        if (s.isDate) {
            val last = AllDay.lastDay(s.date, endProp?.date ?: IcalDuration.parse(component.prop("DURATION")?.value)?.let { s.date.plusDays(maxOf(1, it.days + it.seconds / 86_400)) })
            return@lazy Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(s.date, last))
        }
        val begin = startInstant ?: return@lazy Duration.ZERO
        val end = when {
            endProp != null && !endProp.isDate -> endProp.instant(zones)
            endProp != null -> Zones.instantOf(endProp.date.atStartOfDay(), rules)
            else -> IcalDuration.parse(component.prop("DURATION")?.value)?.let { d ->
                Zones.instantOf(Zones.localOf(begin, rules).plusDays(d.days), rules).plusSeconds(d.seconds)
            } ?: begin
        }
        if (end.isBefore(begin)) Duration.ZERO else Duration.between(begin, end)
    }

    /** All-day: the extra days past the first (0 for a one-day event). */
    val extraDays: Long get() = if (allDay) length.toDays() else 0

    /**
     * Its reminders: null when it has no VALARM at all (the defaults apply), else the alarms counted
     * back from the start, possibly none (an alarm this app can't express, such as the "none" marker
     * other calendars write, counts as having alarms).
     */
    val reminders: List<EventReminder>? by lazy { Alarms.read(component) }

    companion object {
        fun hexColor(value: String): Int? {
            val hex = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matchEntire(value.trim())?.groupValues?.get(1) ?: return null
            val rgb = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex.substring(0, 6)
            return (0xFF000000 or rgb.toLong(16)).toInt()
        }
    }
}

/** A calendar resource as read: its VEVENTs, by UID, with their zones. */
class ParsedResource(val calendar: Component, floating: ZoneId = ZoneId.systemDefault()) {
    val zones = Zones.of(calendar, floating)
    val events: List<EventView> = calendar.children("VEVENT").map { EventView(it, zones) }.filter { it.start != null }
    val masters: List<EventView> get() = events.filter { it.recurrenceId == null }
    fun overridesOf(uid: String): List<EventView> = events.filter { it.uid == uid && it.recurrenceId != null }
    fun master(uid: String): EventView? = events.firstOrNull { it.uid == uid && it.recurrenceId == null }
    val uids: List<String> get() = events.map { it.uid }.distinct()

    companion object {
        fun parse(ics: String, floating: ZoneId = ZoneId.systemDefault()): ParsedResource = ParsedResource(ICalParser.parse(ics), floating)
        fun parseOrNull(ics: String, floating: ZoneId = ZoneId.systemDefault()): ParsedResource? = runCatching { parse(ics, floating) }.getOrNull()
    }
}

/** One date an event is on screen. */
data class Occurrence(
    val resourceId: String,
    val calendarId: String,
    val uid: String,
    /** The original start of this date of a series, as RECURRENCE-ID and EXDATE name it; null for a single event. */
    val recurrenceId: ICalTime?,
    val title: String,
    val location: String,
    val notes: String,
    val allDay: Boolean,
    val start: Instant,
    /** Exclusive: the end of a timed event, midnight after the last day of an all-day one. */
    val end: Instant,
    /** The days it covers, in the zone it was expanded for. */
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val recurring: Boolean,
    val exception: Boolean,
    /** Its own reminders; null when it names none and the defaults apply. */
    val reminders: List<EventReminder>?,
    val color: Int? = null,
) {
    val key: String get() = "$resourceId|${recurrenceId?.let { it.value() + (it.tzid ?: "") } ?: ""}"
    val multiDay: Boolean get() = lastDay.isAfter(firstDay)
}
