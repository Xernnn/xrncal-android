package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Component
import com.xern.xrncal.domain.ical.ICalParser
import com.xern.xrncal.domain.ical.ICalTime
import com.xern.xrncal.domain.ical.ICalWriter
import com.xern.xrncal.domain.ical.Property
import com.xern.xrncal.domain.ical.VTimezones
import com.xern.xrncal.domain.ical.Zones
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** What the editor shows and saves. All-day: [endDate] is the last day covered. */
data class EventDraft(
    val title: String,
    val allDay: Boolean,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val zone: ZoneId,
    /** An RRULE value, or null for none. */
    val rrule: String? = null,
    /** Null: the defaults apply (no VALARM); empty: none. */
    val reminders: List<EventReminder>? = null,
    val location: String = "",
    val notes: String = "",
    /** Its own colour (RFC 7986 COLOR), or null for its calendar's. */
    val color: Int? = null,
) {
    val start: LocalDateTime get() = LocalDateTime.of(startDate, if (allDay) LocalTime.MIDNIGHT else startTime)
    val end: LocalDateTime get() = LocalDateTime.of(endDate, if (allDay) LocalTime.MIDNIGHT else endTime)

    /** Why it can't be saved yet, or null: the end must not come before the start. */
    fun problem(): String? = when {
        allDay && endDate.isBefore(startDate) -> "End before start"
        !allDay && Zones.instantOf(end, zone.rules).isBefore(Zones.instantOf(start, zone.rules)) -> "End before start"
        else -> null
    }
}

/** Which dates of a series an edit or a delete is for: that one, that one and the ones after it, or all. */
enum class Scope { THIS, FOLLOWING, ALL }

/**
 * Changes to a calendar resource, made on the parsed document itself so everything this app doesn't
 * model stays as it was. Each returns the resource's new text, or null when nothing is left of it
 * (the resource is then deleted).
 */
object Edits {
    const val PRODID = "-//xrncal//xrncal Android//EN"

    private fun stamp(now: Instant) = ICalTime.utc(now).value()

    /** A new resource holding one new event. */
    fun create(draft: EventDraft, uid: String, now: Instant): String {
        val cal = Component(
            "VCALENDAR",
            mutableListOf(Property("VERSION", value = "2.0"), Property("PRODID", value = PRODID), Property("CALSCALE", value = "GREGORIAN")),
        )
        val ev = Component("VEVENT", mutableListOf(Property("UID", value = uid), Property("DTSTAMP", value = stamp(now)), Property("CREATED", value = stamp(now))))
        apply(ev, draft, now, series = true)
        ev.set("SEQUENCE", "0")
        cal.components += ev
        ensureZones(cal)
        return ICalWriter.write(cal)
    }

    /** The draft an occurrence opens with: the date tapped, with its event's (or override's) fields. */
    fun draftOf(res: ParsedResource, occ: Occurrence, zone: ZoneId): EventDraft {
        val v = res.events.firstOrNull { it.uid == occ.uid && it.recurrenceId != null && occ.recurrenceId != null && Occurrences.ridKey(it.recurrenceId, it, res.zones) == Occurrences.ridKey(occ.recurrenceId, res.master(occ.uid) ?: it, res.zones) }
            ?: res.master(occ.uid) ?: res.events.first { it.uid == occ.uid }
        val master = res.master(occ.uid)
        val eventZone = v.start?.let { s -> if (s.isDate) zone else if (s.utc) ZoneOffset.UTC else res.zones.zoneId(s.tzid) ?: zone } ?: zone
        val start = if (occ.allDay) occ.firstDay.atStartOfDay() else LocalDateTime.ofInstant(occ.start, eventZone)
        val end = if (occ.allDay) occ.lastDay.atStartOfDay() else LocalDateTime.ofInstant(occ.end, eventZone)
        return EventDraft(
            title = v.title, allDay = occ.allDay,
            startDate = start.toLocalDate(), startTime = start.toLocalTime(),
            endDate = end.toLocalDate(), endTime = end.toLocalTime(),
            zone = eventZone, rrule = master?.rrule, reminders = v.reminders,
            location = v.location, notes = v.notes, color = v.color,
        )
    }

    /**
     * The draft for a whole series opened from one of its dates: the series' own fields and clock
     * time, on that date. A change of date or time then moves every date by as much.
     */
    fun seriesDraft(res: ParsedResource, occ: Occurrence, zone: ZoneId): EventDraft {
        val m = res.master(occ.uid) ?: return draftOf(res, occ, zone)
        val s = m.start!!
        val eventZone = if (s.isDate) zone else if (s.utc) ZoneOffset.UTC else res.zones.zoneId(s.tzid) ?: zone
        val day = origin(m, occ)
        val clock = if (s.isDate) LocalTime.MIDNIGHT else Zones.localOf(m.startInstant!!, m.rules).toLocalTime()
        val start = LocalDateTime.of(day, clock)
        val end = if (s.isDate) start.plusDays(m.extraDays) else LocalDateTime.ofInstant(Zones.instantOf(start, m.rules).plus(m.length), eventZone)
        return EventDraft(
            title = m.title, allDay = s.isDate,
            startDate = start.toLocalDate(), startTime = start.toLocalTime(),
            endDate = end.toLocalDate(), endTime = end.toLocalTime(),
            zone = eventZone, rrule = m.rrule, reminders = m.reminders, location = m.location, notes = m.notes, color = m.color,
        )
    }

    /** The day a series' date stood on before any override moved it, in the series' own zone. */
    private fun origin(series: EventView, occ: Occurrence): LocalDate {
        val rid = occ.recurrenceId ?: return occ.firstDay
        return if (rid.isDate || series.allDay) rid.date else Zones.localOf(rid.instant(series.zones), series.rules).toLocalDate()
    }

    /**
     * A single event changed, or one date of a series ([Scope.THIS], an override), or the whole
     * series ([Scope.ALL], with a draft from [seriesDraft]). [Scope.FOLLOWING] is [split]'s.
     */
    fun update(ics: String, occ: Occurrence, draft: EventDraft, scope: Scope, now: Instant): String {
        val cal = ICalParser.parse(ics)
        val res = ParsedResource(cal, draft.zone)
        val master = res.master(occ.uid)
        if (occ.recurrenceId != null && (scope == Scope.THIS || master == null)) {
            val o = overrideFor(res, cal, occ, now)
            apply(o, draft.copy(rrule = null), now, series = false)
            bump(o)
        } else {
            val ev = master ?: res.events.first { it.uid == occ.uid }
            var d = draft
            if (ev.isSeries && occ.recurrenceId != null) {
                val days = ChronoUnit.DAYS.between(origin(ev, occ), draft.startDate)
                retimeSeries(res, ev, days, draft)
                val s = ev.start!!
                val first = if (s.isDate) s.date else Zones.localOf(ev.startInstant!!, ev.rules).toLocalDate()
                val span = ChronoUnit.DAYS.between(draft.startDate, draft.endDate)
                d = draft.copy(startDate = first.plusDays(days), endDate = first.plusDays(days + span))
            }
            apply(ev.component, d, now, series = true)
            bump(ev.component)
            // No longer a series: its changed dates have nothing to belong to.
            if (d.rrule == null) cal.components.removeAll { it.name == "VEVENT" && it !== ev.component && it.prop("UID")?.value?.trim() == ev.uid && it.has("RECURRENCE-ID") }
        }
        ensureZones(cal)
        return ICalWriter.write(cal)
    }

    /**
     * "This and following" for an edit: [occ]'s series split at its date, as the laptop does. The
     * series as it was ends the moment before that date (UNTIL), its later EXDATEs, RDATEs and
     * changed dates leaving it. A new series under [newUid] starts on the date with the draft's
     * fields and takes those along, moved as the date was; unless the editor gave it a rule of its
     * own it repeats as the old one did from there: to the same end, or for the dates its COUNT had
     * left, on days moved with its start. Returns (the series as it was, the new one), or null when
     * no date comes before [occ]'s: the edit is then the whole series'.
     */
    fun split(ics: String, occ: Occurrence, draft: EventDraft, newUid: String, now: Instant): Pair<String, String>? {
        val rid = occ.recurrenceId ?: return null
        val old = ICalParser.parse(ics)
        val master = ParsedResource(old, draft.zone).master(occ.uid)?.takeIf { it.isSeries } ?: return null
        if (fromOn(master.start!!, rid, master)) return null
        val key = Occurrences.ridKey(rid, master, master.zones)

        val rest = ICalParser.parse(ics)
        rest.components.removeAll { c ->
            c.name == "VEVENT" && (
                c.prop("UID")?.value?.trim() != occ.uid ||
                    c.has("RECURRENCE-ID") && (ICalTime.of(c.prop("RECURRENCE-ID"))?.let { t -> !fromOn(t, rid, master) || Occurrences.ridKey(t, master, master.zones) == key } ?: true)
                )
        }
        val head = rest.children("VEVENT").first { !it.has("RECURRENCE-ID") }
        keepDates(head, "EXDATE") { fromOn(it, rid, master) }
        keepDates(head, "RDATE") { fromOn(it, rid, master) }
        val start = inFormOf(master, rid)
        val end = if (master.allDay) ICalTime.date(start.date.plusDays(master.extraDays + 1)) else inFormOf(master, ICalTime.utc(start.instant(master.zones).plus(master.length)))
        head.set("DTSTART", start.value(), start.params())
        head.remove("DURATION")
        head.set("DTEND", end.value(), end.params())
        val left = master.rrule?.let { restOfRule(master, it, rid) }
        left?.let { head.set("RRULE", it) }
        head.set("SEQUENCE", "0")
        head.set("CREATED", stamp(now))
        for (c in rest.children("VEVENT")) c.set("UID", newUid)
        // The editor's rule if it was changed there; else the old one's, its days moved with the start.
        val unchanged = draft.rrule == master.rrule || draft.rrule == master.rrule?.let { RepeatRules.fitUntil(it, draft.allDay, draft.zone) }
        val rule = if (draft.rrule == null || !unchanged || left == null) draft.rrule else RepeatRules.forMovedStart(left, localDay(rid, master), draft.startDate)
        // A rule of its own has dates of its own: the old pattern's deleted and changed ones don't belong.
        if (!unchanged) {
            head.remove("EXDATE")
            rest.components.removeAll { it.name == "VEVENT" && it.has("RECURRENCE-ID") }
        }
        val first = occ.copy(uid = newUid, recurrenceId = start)
        val after = update(ICalWriter.write(rest), first, draft.copy(rrule = rule), Scope.ALL, now)

        endBefore(old, master, rid, now)
        return ICalWriter.write(old) to after
    }

    /** [m]'s series ended the moment before [rid]: UNTIL, and its dates from [rid] on taken out. */
    private fun endBefore(cal: Component, m: EventView, rid: ICalTime, now: Instant) {
        m.rrule?.let { rule ->
            val s = m.start!!
            val until = when {
                m.allDay -> localDay(rid, m).minusDays(1).format(ICalTime.DATE)
                s.utc || s.tzid != null -> ICalTime.utc(rid.instant(m.zones).minusSeconds(1)).value()
                else -> Zones.localOf(rid.instant(m.zones).minusSeconds(1), m.rules).format(ICalTime.DATE_TIME)
            }
            val parts = rule.split(';').filter { it.isNotBlank() && !it.startsWith("COUNT=", true) && !it.startsWith("UNTIL=", true) }
            m.component.set("RRULE", (parts + "UNTIL=$until").joinToString(";"))
        }
        keepDates(m.component, "EXDATE") { !fromOn(it, rid, m) }
        keepDates(m.component, "RDATE") { !fromOn(it, rid, m) }
        cal.components.removeAll { c ->
            c.name == "VEVENT" && c.prop("UID")?.value?.trim() == m.uid && c.has("RECURRENCE-ID") &&
                ICalTime.of(c.prop("RECURRENCE-ID"))?.let { fromOn(it, rid, m) } == true
        }
        bump(m.component)
        m.component.set("LAST-MODIFIED", stamp(now))
        m.component.set("DTSTAMP", stamp(now))
    }

    /** Whether [t] (an EXDATE, RDATE or RECURRENCE-ID of [m]) is [rid]'s date or a later one. */
    private fun fromOn(t: ICalTime, rid: ICalTime, m: EventView): Boolean =
        if (m.allDay || t.isDate || rid.isDate) !localDay(t, m).isBefore(localDay(rid, m)) else !t.instant(m.zones).isBefore(rid.instant(m.zones))

    private fun localDay(t: ICalTime, m: EventView): LocalDate = if (t.isDate) t.date else Zones.localOf(t.instant(m.zones), m.rules).toLocalDate()

    /** [rule] for the dates from [rid] on: a COUNT less the dates before (RFC 5545 counts deleted ones too), an UNTIL as it was. */
    private fun restOfRule(m: EventView, rule: String, rid: ICalTime): String {
        val count = Regex("""(^|;)COUNT=(\d+)""", RegexOption.IGNORE_CASE).find(rule) ?: return rule
        val s = m.start!!
        val before = try {
            if (m.allDay) Recurrence.between(rule, s.local, true, s.local, localDay(rid, m).minusDays(1).atStartOfDay()).size
            else Recurrence.between(rule, s.local, false, s.local, Zones.localOf(rid.instant(m.zones).minusSeconds(1), m.rules)) { utc -> Zones.localOf(utc.toInstant(ZoneOffset.UTC), m.rules) }.size
        } catch (e: Recurrence.Unexpandable) {
            0
        }
        return rule.replace(count.value, count.groupValues[1] + "COUNT=" + maxOf(1, count.groupValues[2].toInt() - before))
    }

    /** Keeps the values of [name] (EXDATE or RDATE, each a list) that [keep] takes; a property left with none goes. */
    private fun keepDates(ev: Component, name: String, keep: (ICalTime) -> Boolean) {
        for (p in ev.props(name)) {
            val tzid = p.param("TZID")?.let(ICalParser::unquote)
            val kept = p.value.split(',').filter { raw -> ICalTime.parse(raw.substringBefore('/'), tzid)?.let(keep) ?: true }
            if (kept.isEmpty()) ev.properties.remove(p) else p.value = kept.joinToString(",")
        }
    }

    /**
     * One date of a series taken away (EXDATE, its override gone), that date and the ones after it
     * ([Scope.FOLLOWING]: the series ends the moment before), or the whole event: null then.
     */
    fun delete(ics: String, occ: Occurrence, scope: Scope, now: Instant): String? {
        val rid0 = occ.recurrenceId
        if (scope == Scope.FOLLOWING && rid0 != null) {
            val cal = ICalParser.parse(ics)
            val master = ParsedResource(cal).master(occ.uid)
            if (master != null && master.isSeries && !fromOn(master.start!!, rid0, master)) {
                endBefore(cal, master, rid0, now)
                return ICalWriter.write(cal)
            }
        }
        if (occ.recurrenceId == null || scope != Scope.THIS) {
            val cal = ICalParser.parse(ics)
            cal.components.removeAll { it.name == "VEVENT" && it.prop("UID")?.value?.trim() == occ.uid }
            return if (cal.children("VEVENT").isEmpty()) null else ICalWriter.write(cal)
        }
        val cal = ICalParser.parse(ics)
        val res = ParsedResource(cal)
        val master = res.master(occ.uid)
        val rid = occ.recurrenceId
        // The override for that date goes, whatever form its RECURRENCE-ID was written in.
        cal.components.removeAll { c ->
            c.name == "VEVENT" && c.prop("UID")?.value?.trim() == occ.uid && c.has("RECURRENCE-ID") &&
                ICalTime.of(c.prop("RECURRENCE-ID"))?.let { Occurrences.ridKey(it, master ?: EventView(c, res.zones), res.zones) } == Occurrences.ridKey(rid, master ?: EventView(c, res.zones), res.zones)
        }
        if (master == null) return if (cal.children("VEVENT").isEmpty()) null else ICalWriter.write(cal)
        master.component.properties += exdate(master, rid)
        bump(master.component)
        master.component.set("LAST-MODIFIED", stamp(now))
        master.component.set("DTSTAMP", stamp(now))
        return ICalWriter.write(cal)
    }

    /** EXDATE for [rid] in the form of the series' DTSTART. */
    private fun exdate(master: EventView, rid: ICalTime): Property {
        val form = inFormOf(master, rid)
        return Property("EXDATE", form.params(), form.value())
    }

    /** [rid] as the series' DTSTART writes times: a date, UTC, or the same TZID. */
    private fun inFormOf(master: EventView, rid: ICalTime): ICalTime {
        val s = master.start!!
        return when {
            s.isDate -> ICalTime.date(rid.date)
            s.utc -> ICalTime.utc(rid.instant(master.zones))
            rid.isDate -> ICalTime(rid.date, s.time, tzid = s.tzid)
            else -> {
                val local = Zones.localOf(rid.instant(master.zones), master.rules)
                ICalTime(local.toLocalDate(), local.toLocalTime(), tzid = s.tzid)
            }
        }
    }

    /** The override VEVENT for [occ]'s date, made from the series when there is none yet. */
    private fun overrideFor(res: ParsedResource, cal: Component, occ: Occurrence, now: Instant): Component {
        val rid = occ.recurrenceId!!
        val master = res.master(occ.uid)
        res.overridesOf(occ.uid).firstOrNull { Occurrences.ridKey(it.recurrenceId!!, master ?: it, res.zones) == Occurrences.ridKey(rid, master ?: it, res.zones) }?.let { return it.component }
        val m = master ?: error("no series for ${occ.uid}")
        val o = m.component.copy()
        listOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID").forEach(o::remove)
        val form = inFormOf(m, rid)
        o.properties.add(minOf(o.properties.size, 1), Property("RECURRENCE-ID", form.params(), form.value()))
        o.set("DTSTAMP", stamp(now))
        cal.components.add(cal.components.indexOf(m.component) + 1, o)
        return o
    }

    /**
     * A series given a new time (or made all-day, or timed): its EXDATEs and overrides' RECURRENCE-IDs
     * move with it, so a date taken out or changed stays the date it was.
     */
    private fun retimeSeries(res: ParsedResource, series: EventView, days: Long, draft: EventDraft) {
        val s = series.start!!
        val oldLocal = if (s.isDate) s.date.atStartOfDay() else Zones.localOf(series.startInstant!!, series.rules)
        val newTime = if (draft.allDay) null else draft.startTime
        val sameTime = s.isDate == draft.allDay && (draft.allDay || oldLocal.toLocalTime() == newTime) && days == 0L &&
            (draft.allDay || s.utc && Zones.isUtc(draft.zone.id) || s.tzid == draft.zone.id)
        if (sameTime) return
        fun moved(t: ICalTime): ICalTime {
            val local = if (t.isDate) t.date else Zones.localOf(t.instant(series.zones), series.rules).toLocalDate()
            val date = local.plusDays(days)
            return if (newTime == null) ICalTime.date(date) else ICalTime.zoned(LocalDateTime.of(date, newTime), draft.zone)
        }
        val ex = series.component.props("EXDATE").flatMap(ICalTime::list).map(::moved)
        series.component.remove("EXDATE")
        for (t in ex) series.component.properties += Property("EXDATE", t.params(), t.value())
        for (o in res.overridesOf(series.uid)) {
            val t = moved(o.recurrenceId!!)
            o.component.set("RECURRENCE-ID", t.value(), t.params())
        }
        // An UNTIL with a new kind of start keeps its day.
        series.rrule?.let { rule ->
            val m = Regex("""UNTIL=(\d{8})(T\d{6}Z?)?""", RegexOption.IGNORE_CASE).find(rule) ?: return@let
            val day = LocalDate.parse(m.groupValues[1], ICalTime.DATE)
            series.component.set("RRULE", rule.replace(m.value, "UNTIL=" + RepeatRules.until(day, draft.allDay, draft.zone)))
        }
    }

    /** The draft's fields written into [ev]: times, text, alarms, and (for a series) its rule. */
    private fun apply(ev: Component, draft: EventDraft, now: Instant, series: Boolean) {
        ev.setText("SUMMARY", draft.title.trim().ifEmpty { "" })
        if (draft.title.isBlank()) ev.remove("SUMMARY")
        val (start, end) = times(draft)
        ev.set("DTSTART", start.value(), start.params())
        ev.remove("DURATION")
        ev.set("DTEND", end.value(), end.params())
        ev.setText("LOCATION", draft.location.trim().takeIf { it.isNotEmpty() })
        ev.setText("DESCRIPTION", draft.notes.takeIf { it.isNotBlank() })
        when {
            draft.color != null -> ev.set("COLOR", Palette.hexOf(draft.color))
            // A colour this app can't read (a CSS name from another program) is left as it was.
            ev.prop("COLOR")?.value?.let(EventView::hexColor) != null -> ev.remove("COLOR")
        }
        if (series) {
            val rule = draft.rrule?.let { RepeatRules.fitUntil(it, draft.allDay, draft.zone) }
            if (rule == null) {
                ev.remove("RRULE")
                ev.remove("EXDATE")
                ev.remove("RDATE")
            } else {
                ev.set("RRULE", rule)
            }
        }
        Alarms.write(ev, draft.reminders, draft.title)
        ev.set("DTSTAMP", stamp(now))
        ev.set("LAST-MODIFIED", stamp(now))
    }

    fun times(draft: EventDraft): Pair<ICalTime, ICalTime> = if (draft.allDay) {
        ICalTime.date(draft.startDate) to ICalTime.date(AllDay.exclusiveEnd(draft.startDate, draft.endDate))
    } else {
        ICalTime.zoned(draft.start, draft.zone) to ICalTime.zoned(draft.end, draft.zone)
    }

    private fun bump(ev: Component) {
        val n = ev.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0
        ev.set("SEQUENCE", (n + 1).toString())
    }

    /** A VTIMEZONE for every TZID the resource names that the zone database knows and the file lacks. */
    fun ensureZones(cal: Component) {
        val have = cal.children("VTIMEZONE").mapNotNull { it.prop("TZID")?.value?.trim() }.toSet()
        val used = linkedMapOf<String, Int>()
        fun scan(c: Component) {
            for (p in c.properties) {
                val tzid = p.param("TZID")?.let(ICalParser::unquote) ?: continue
                val year = ICalTime.parse(p.value.substringBefore(',').substringBefore('/'))?.date?.year ?: continue
                used[tzid] = minOf(year, used[tzid] ?: year)
            }
            c.components.forEach(::scan)
        }
        cal.children("VEVENT").forEach(::scan)
        var at = cal.components.indexOfFirst { it.name != "VTIMEZONE" }.let { if (it < 0) cal.components.size else it }
        for ((tzid, year) in used) {
            if (tzid in have) continue
            val zone = Zones.ianaFor(tzid)?.takeIf { it.id == tzid } ?: continue
            cal.components.add(at++, VTimezones.of(zone, year))
        }
    }

    /** A copy of the resource with every event's UID kept: what a calendar move writes into the new calendar. */
    fun copy(ics: String): String = ICalWriter.write(ICalParser.parse(ics))
}
