package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Component
import com.xern.xrncal.domain.ical.ICalParser
import com.xern.xrncal.domain.ical.ICalWriter
import com.xern.xrncal.domain.ical.Property
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * An .ics file opened from another app (docs/spec.md, "Other apps"): a tap in Files, a mail's
 * attachment, a download. One event opens the editor as a new event; several are imported into a
 * calendar, each event (a UID's VEVENTs: a series and its changed dates) as a resource of its own,
 * written as the file has it.
 */
object IcsFiles {
    const val NOT_CALENDAR = "Not a calendar file"
    const val NO_EVENTS = "No events"

    /** What a file holds. */
    sealed interface Read {
        /** One VEVENT: the editor's draft. */
        data class One(val draft: EventDraft) : Read

        /** Several: the events to import, in the file's order. */
        data class Many(val events: List<FileEvent>) : Read {
            val first: LocalDate get() = events.minOf { it.first }
            val last: LocalDate get() = events.maxOf { it.last }
        }

        /** Not iCalendar, or no event in it: [line] says which. */
        data class Unreadable(val line: String) : Read
    }

    /**
     * One event of a file: its UID (blank when the file gives none), the days its first date
     * covers, and its VEVENTs as a resource of their own, with the time zones they name.
     */
    data class FileEvent(val uid: String, val title: String, val first: LocalDate, val last: LocalDate, val ics: String)

    /** What importing into a calendar does: the events to add, each under the UID it is saved with, and how many it holds already. */
    data class ImportPlan(val add: List<FileEvent>, val alreadyThere: Int)

    /** [text] read in [zone]; a single event's reminders are its own, or [defaults] for a new event when it has none. */
    fun read(text: String, zone: ZoneId, defaults: Reminders.Defaults): Read {
        return try {
            val root = ICalParser.parse(text)
            if (!root.name.equals("VCALENDAR", true)) return Read.Unreadable(NOT_CALENDAR)
            val res = ParsedResource(root, zone)
            when (res.events.size) {
                0 -> Read.Unreadable(NO_EVENTS)
                1 -> Read.One(draftOf(res.events.single(), zone, defaults))
                else -> {
                    val groups = LinkedHashMap<String, MutableList<EventView>>()
                    res.events.forEachIndexed { i, v -> groups.getOrPut(v.uid.ifEmpty { "\u0000$i" }) { mutableListOf() } += v }
                    Read.Many(groups.values.map { fileEvent(root, it, zone) })
                }
            }
        } catch (e: RuntimeException) {
            Read.Unreadable(NOT_CALENDAR)
        }
    }

    /**
     * The import into [calendar], which holds the UIDs [here]: an event it holds is there already
     * and skipped, so opening the same file twice adds nothing the second time. The rest keep
     * their UID, unless another calendar on the phone holds it ([elsewhere]): such an event is
     * saved under [copyUid], so no two calendars share an event's UID and a second import still
     * finds it. An event without a UID gets a [fresh] one.
     */
    fun plan(events: List<FileEvent>, calendar: String, here: Set<String>, elsewhere: Set<String>, fresh: () -> String = { UUID.randomUUID().toString() }): ImportPlan {
        var there = 0
        val add = ArrayList<FileEvent>()
        for (e in events) {
            val uid = when {
                e.uid.isEmpty() -> fresh()
                e.uid !in here && e.uid in elsewhere -> copyUid(e.uid, calendar)
                else -> e.uid
            }
            when {
                uid in here -> there++
                uid == e.uid -> add += e
                else -> add += e.copy(uid = uid, ics = withUid(e.ics, uid))
            }
        }
        return ImportPlan(add, there)
    }

    /** The UID an event another calendar holds takes when imported into [calendar]: the same each time. */
    fun copyUid(uid: String, calendar: String): String = UUID.nameUUIDFromBytes("$calendar\n$uid".toByteArray(Charsets.UTF_8)).toString()

    /** The editor's draft for a file's one event: its fields, in its own zone when the zone database knows it, else the phone's. */
    private fun draftOf(v: EventView, zone: ZoneId, defaults: Reminders.Defaults): EventDraft {
        val s = v.start!!
        val notes = InsertRequest.withGuests(v.notes, InsertRequest.emails(v.component.props("ATTENDEE").map { it.value }))
        val reminders = v.reminders ?: defaults.forNew(s.isDate)
        val rule = InsertRequest.readableRule(v.rrule)
        if (s.isDate) {
            return EventDraft(v.title, true, s.date, LocalTime.MIDNIGHT, s.date.plusDays(v.extraDays), LocalTime.MIDNIGHT, zone, rule, reminders, v.location, notes, v.color)
        }
        val eventZone = if (s.utc || s.tzid == null) zone else v.zones.zoneId(s.tzid) ?: zone
        val begin = v.startInstant!!
        val start = begin.atZone(eventZone).toLocalDateTime()
        val end = begin.plus(v.length).atZone(eventZone).toLocalDateTime()
        return EventDraft(v.title, false, start.toLocalDate(), start.toLocalTime(), end.toLocalDate(), end.toLocalTime(), eventZone, rule, reminders, v.location, notes, v.color)
    }

    private fun fileEvent(root: Component, group: List<EventView>, zone: ZoneId): FileEvent {
        val main = group.firstOrNull { it.recurrenceId == null } ?: group.first()
        val s = main.start!!
        val first: LocalDate
        val last: LocalDate
        if (s.isDate) {
            first = s.date
            last = s.date.plusDays(main.extraDays)
        } else {
            val begin = main.startInstant!!
            first = begin.atZone(zone).toLocalDate()
            last = if (main.length.isZero) first else begin.plus(main.length).minusNanos(1).atZone(zone).toLocalDate()
        }
        val cal = Component("VCALENDAR", mutableListOf(Property("VERSION", value = "2.0"), Property("PRODID", value = root.prop("PRODID")?.value ?: Edits.PRODID)))
        root.prop("CALSCALE")?.let { cal.properties += it.copy() }
        val tzids = group.flatMap { tzidsIn(it.component) }.toSet()
        for (tz in root.children("VTIMEZONE")) if (tz.prop("TZID")?.value?.trim() in tzids) cal.components += tz.copy()
        for (v in group) cal.components += v.component.copy()
        Edits.ensureZones(cal)
        return FileEvent(main.uid, main.title, first, last, ICalWriter.write(cal))
    }

    private fun tzidsIn(c: Component): List<String> =
        c.properties.mapNotNull { p -> p.param("TZID")?.let(ICalParser::unquote)?.trim() } + c.components.flatMap(::tzidsIn)

    /** [ics] with every VEVENT under [uid]. */
    private fun withUid(ics: String, uid: String): String {
        val cal = ICalParser.parse(ics)
        for (v in cal.children("VEVENT")) {
            v.set("UID", uid)
        }
        return ICalWriter.write(cal)
    }
}
