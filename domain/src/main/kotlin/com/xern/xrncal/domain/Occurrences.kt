package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Component
import com.xern.xrncal.domain.ical.ICalTime
import com.xern.xrncal.domain.ical.Property
import com.xern.xrncal.domain.ical.Zones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A VEVENT's alarms as reminders, and back (the laptop app's parse-ics and write-ics). */
object Alarms {
    /**
     * The "none" marker other calendars write for an event set to ring never: an alarm at a fixed
     * time in 1976 that does nothing. Read as no reminders. Phase 1 wrote it for an event whose
     * reminders were all removed, when no VALARM meant "the defaults"; since an event without a
     * VALARM never rings (on the laptop too), it says nothing any more and is not written.
     */
    const val NONE_TRIGGER = "19760401T005545Z"

    fun read(vevent: Component): List<EventReminder>? {
        val alarms = vevent.children("VALARM")
        if (alarms.isEmpty()) return null
        return Reminders.normalize(alarms.mapNotNull(::reminderOf))
    }

    /** The reminder a VALARM is, or null for one counted from the end or set at a fixed time. */
    fun reminderOf(alarm: Component): EventReminder? {
        val trigger = alarm.prop("TRIGGER") ?: return null
        if (trigger.param("VALUE").equals("DATE-TIME", true)) return null
        val related = trigger.param("RELATED")?.uppercase() ?: "START"
        if (related != "START") return null
        val d = IcalDuration.parse(trigger.value) ?: return null
        val minutes = -d.totalMinutes
        if (minutes < 0) return null
        val action = alarm.prop("ACTION")?.value?.trim()?.uppercase()
        if (action == "NONE") return null
        return EventReminder(minutes.toInt().coerceAtMost(Reminders.MAX_MINUTES), email = action == "EMAIL")
    }

    /**
     * Writes [reminders] as the VEVENT's alarms. Null removes them all. An alarm already there for a
     * minute still wanted is kept as it is, with its sound or whatever else another client put in
     * it; email alarms and ones this app can't read are kept as well. The "none" marker goes: an
     * event left with no reminders simply has no VALARM, which never rings.
     */
    fun write(vevent: Component, reminders: List<EventReminder>?, title: String) {
        val alarms = vevent.children("VALARM")
        if (reminders == null) {
            vevent.components.removeAll(alarms.toSet())
            return
        }
        val wanted = Reminders.forCalDav(reminders).map { it.minutesBefore }.toMutableSet()
        val keep = mutableSetOf<Component>()
        for (a in alarms) {
            val r = reminderOf(a)
            when {
                r == null && isNoneMarker(a) -> Unit
                r == null -> keep += a // relative to the end, or at a fixed time: not this app's to drop
                r.email -> keep += a
                r.minutesBefore in wanted -> {
                    keep += a
                    wanted -= r.minutesBefore
                }
            }
        }
        vevent.components.removeAll { it.name.equals("VALARM", true) && it !in keep }
        for (m in wanted.sorted()) vevent.components += display(m, title)
    }

    fun display(minutes: Int, title: String) = Component(
        "VALARM",
        mutableListOf(
            Property("ACTION", value = "DISPLAY"),
            Property("DESCRIPTION", value = com.xern.xrncal.domain.ical.ICalText.escape(title.ifBlank { "Reminder" })),
            Property("TRIGGER", value = IcalDuration.before(minutes)),
        ),
    )

    private fun isNoneMarker(a: Component): Boolean =
        a.prop("ACTION")?.value?.trim().equals("NONE", true) || a.prop("TRIGGER")?.value?.trim() == NONE_TRIGGER
}

/**
 * The dates of a resource's events between two days, in the phone's zone. A series' dates come from
 * its rule and RDATEs, less its EXDATEs; a date with an override (RECURRENCE-ID) shows as the override
 * says, where the override says, and one cancelled there not at all. Overrides whose series isn't in
 * the resource (an invitation to one date) show on their own.
 */
object Occurrences {
    /** No date of a series is looked for further back than this before the range, however long it lasts. */
    val MAX_LOOK_BACK: Duration = Duration.ofDays(366)

    fun expand(
        res: ParsedResource,
        resourceId: String,
        calendarId: String,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        budget: Recurrence.Budget? = null,
    ): List<Occurrence> {
        val rangeStart = from.atStartOfDay(zone).toInstant()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant()
        val out = mutableListOf<Occurrence>()
        for (uid in res.uids) {
            val master = res.master(uid)
            val overrides = res.overridesOf(uid)
            val excluded = master?.let { exdateKeys(it) } ?: emptySet()
            val overridden = HashSet<String>()
            for (o in overrides) {
                val rid = o.recurrenceId ?: continue
                val key = ridKey(rid, master ?: o, res.zones)
                overridden += key
                if (o.cancelled || key in excluded) continue
                single(o, resourceId, calendarId, zone, rangeStart, rangeEnd, from, to, rid, recurring = master != null, exception = true)?.let { out += it }
            }
            if (master == null || master.cancelled) continue
            if (!master.isSeries) {
                single(master, resourceId, calendarId, zone, rangeStart, rangeEnd, from, to, null, recurring = false, exception = false)?.let { out += it }
                continue
            }
            for (rid in seriesDates(master, rangeStart, rangeEnd, from, to, budget)) {
                val key = ridKey(rid, master, res.zones)
                if (key in overridden || key in excluded) continue
                if (!rid.isDate && master.allDay.not() && ("D" + localDate(rid, master).toString()) in excluded) continue
                dateOf(master, rid, resourceId, calendarId, zone, rangeStart, rangeEnd, from, to)?.let { out += it }
            }
        }
        return out.sortedWith(compareBy<Occurrence>({ it.firstDay }, { !it.allDay }, { it.start }, { it.title }))
    }

    /** Every date of a series between the bounds, as RECURRENCE-IDs in the form of its DTSTART. */
    private fun seriesDates(m: EventView, rangeStart: Instant, rangeEnd: Instant, from: LocalDate, to: LocalDate, budget: Recurrence.Budget?): List<ICalTime> {
        val start = m.start!!
        val dates = mutableListOf<ICalTime>()
        if (m.allDay) {
            val back = minOf(m.extraDays, MAX_LOOK_BACK.toDays())
            val lo = from.minusDays(back)
            m.rrule?.let { rule ->
                try {
                    Recurrence.between(rule, start.local, true, lo.atStartOfDay(), to.atStartOfDay(), budget).forEach { dates += ICalTime.date(it.toLocalDate()) }
                } catch (e: Recurrence.Unexpandable) {
                    dates += start
                }
            } ?: run { dates += start }
            m.rdates.forEach { r -> if (!r.date.isBefore(lo) && !r.date.isAfter(to)) dates += ICalTime.date(r.date) }
        } else {
            val rules = m.rules
            val lo = Zones.localOf(rangeStart.minus(minOf(m.length, MAX_LOOK_BACK)), rules)
            val hi = Zones.localOf(rangeEnd, rules)
            val form = { local: LocalDateTime ->
                when {
                    start.utc -> ICalTime.utc(Zones.instantOf(local, rules))
                    else -> ICalTime(local.toLocalDate(), local.toLocalTime(), tzid = start.tzid)
                }
            }
            m.rrule?.let { rule ->
                try {
                    Recurrence.between(rule, start.local, false, lo, hi, budget) { utc -> Zones.localOf(utc.toInstant(ZoneOffset.UTC), rules) }.forEach { dates += form(it) }
                } catch (e: Recurrence.Unexpandable) {
                    dates += start
                }
            } ?: run { dates += start }
            for (r in m.rdates) {
                val local = if (r.isDate) r.date.atTime(start.time) else Zones.localOf(r.instant(m.zones), rules)
                if (!local.isBefore(lo) && !local.isAfter(hi)) dates += form(local)
            }
        }
        return dates.distinctBy { it.value() }
    }

    private fun localDate(rid: ICalTime, m: EventView): LocalDate =
        if (rid.isDate) rid.date else Zones.localOf(rid.instant(m.zones), m.rules).toLocalDate()

    /**
     * What a RECURRENCE-ID or EXDATE is compared by: the date for an all-day series, the instant
     * for a timed one, so `...Z` and a TZID form of the same moment match. A bare date against a
     * timed series names the day.
     */
    fun ridKey(rid: ICalTime, series: EventView, zones: Zones): String = when {
        series.allDay || rid.isDate -> "D" + rid.date
        else -> "T" + rid.instant(zones).epochSecond
    }

    private fun exdateKeys(m: EventView): Set<String> = m.exdates.map { ridKey(it, m, m.zones) }.toSet()

    /** One date of a series, timed or all-day, as the series says. */
    private fun dateOf(
        m: EventView, rid: ICalTime, resourceId: String, calendarId: String, zone: ZoneId,
        rangeStart: Instant, rangeEnd: Instant, from: LocalDate, to: LocalDate,
    ): Occurrence? {
        if (m.allDay) {
            val first = rid.date
            val last = first.plusDays(m.extraDays)
            if (first.isAfter(to) || last.isBefore(from)) return null
            return make(m, resourceId, calendarId, rid, true, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), first, last, zone, true, false)
        }
        val s = rid.instant(m.zones)
        val e = s.plus(m.length)
        if (!overlaps(s, e, rangeStart, rangeEnd)) return null
        return make(m, resourceId, calendarId, rid, false, s, e, null, null, zone, true, false)
    }

    /** A VEVENT that stands for one date: a single event, or an override. */
    private fun single(
        v: EventView, resourceId: String, calendarId: String, zone: ZoneId,
        rangeStart: Instant, rangeEnd: Instant, from: LocalDate, to: LocalDate,
        rid: ICalTime?, recurring: Boolean, exception: Boolean,
    ): Occurrence? {
        val start = v.start ?: return null
        if (v.allDay) {
            val first = start.date
            val last = first.plusDays(v.extraDays)
            if (first.isAfter(to) || last.isBefore(from)) return null
            return make(v, resourceId, calendarId, rid, true, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), first, last, zone, recurring, exception)
        }
        val s = v.startInstant ?: return null
        val e = s.plus(v.length)
        if (!overlaps(s, e, rangeStart, rangeEnd)) return null
        return make(v, resourceId, calendarId, rid, false, s, e, null, null, zone, recurring, exception)
    }

    private fun overlaps(s: Instant, e: Instant, rangeStart: Instant, rangeEnd: Instant): Boolean =
        s.isBefore(rangeEnd) && (e.isAfter(rangeStart) || (s == e && !s.isBefore(rangeStart)))

    private fun make(
        v: EventView, resourceId: String, calendarId: String, rid: ICalTime?, allDay: Boolean,
        s: Instant, e: Instant, first: LocalDate?, last: LocalDate?, zone: ZoneId, recurring: Boolean, exception: Boolean,
    ): Occurrence {
        val firstDay = first ?: s.atZone(zone).toLocalDate()
        // An end at midnight belongs to the day before: 22:00 to 24:00 is one day.
        val lastDay = last ?: (if (e.isAfter(s)) e.minusNanos(1) else e).atZone(zone).toLocalDate()
        return Occurrence(
            resourceId = resourceId, calendarId = calendarId, uid = v.uid, recurrenceId = rid,
            title = v.title, location = v.location, notes = v.notes, allDay = allDay,
            start = s, end = e, firstDay = firstDay, lastDay = maxOf(firstDay, lastDay),
            recurring = recurring, exception = exception, reminders = v.reminders, color = v.color,
        )
    }

    /** The next date of an event not yet over at [now] (within two years), or null: search's Future tab. */
    fun next(res: ParsedResource, resourceId: String, calendarId: String, now: Instant, zone: ZoneId): Occurrence? {
        val today = now.atZone(zone).toLocalDate()
        return expand(res, resourceId, calendarId, today, today.plusYears(2), zone).firstOrNull { !it.end.isBefore(now) && it.end != now || (it.start == it.end && !it.start.isBefore(now)) }
    }

    /** The last date of an event over by [now] (within [years] years back), or null: search's Past tab. */
    fun last(res: ParsedResource, resourceId: String, calendarId: String, now: Instant, zone: ZoneId, years: Long = 2): Occurrence? {
        val today = now.atZone(zone).toLocalDate()
        return expand(res, resourceId, calendarId, today.minusYears(years), today, zone).lastOrNull { !it.end.isAfter(now) && !(it.start == it.end && !it.start.isBefore(now)) }
    }

    /**
     * The date of an event nearest to [now]: the next one to come, else the last one before. What a
     * search result shows and opens for a series.
     */
    fun nearest(res: ParsedResource, resourceId: String, calendarId: String, now: Instant, zone: ZoneId): Occurrence? {
        val today = now.atZone(zone).toLocalDate()
        val ahead = expand(res, resourceId, calendarId, today, today.plusYears(2), zone)
        ahead.firstOrNull { !it.end.isBefore(now) }?.let { return it }
        val behind = expand(res, resourceId, calendarId, today.minusYears(2), today, zone)
        if (behind.isNotEmpty()) return behind.last()
        val first = res.masters.firstOrNull() ?: res.events.firstOrNull() ?: return null
        val d = first.start!!.let { if (it.isDate) it.date else Zones.localOf(first.startInstant!!, zone.rules).toLocalDate() }
        return expand(res, resourceId, calendarId, d, d.plusDays(1), zone).firstOrNull()
    }
}
