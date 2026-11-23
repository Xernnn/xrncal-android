package com.xern.xrncal.data

import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.Edits
import com.xern.xrncal.domain.HolidayCalendar
import com.xern.xrncal.domain.Holidays
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Occurrences
import com.xern.xrncal.domain.ParsedResource
import com.xern.xrncal.domain.Recurrence
import com.xern.xrncal.domain.Scope
import com.xern.xrncal.domain.TextFold
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A calendar as the drawer and the editor show it: the stored ones and the holiday calendars. */
data class CalendarInfo(val id: String, val name: String, val color: Int, val readOnly: Boolean, val shown: Boolean, val group: String)

/** A resource that couldn't be sent, for Settings to list. */
data class Problem(val id: String, val title: String, val calendar: String, val problem: String)

/**
 * The events the screens show and change. Reading parses the resources of the shown calendars that
 * can have dates in the range (kept parsed while they don't change) and expands them in the phone's
 * zone; every change goes to the store as the resource's new text, marked to be sent, and [changed]
 * then books a sync and sets the reminders again.
 */
class EventRepository(
    private val store: CalendarStore,
    private val settings: () -> Settings,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
    private val work: CoroutineContext = Dispatchers.Default,
    private val changed: () -> Unit = {},
) {
    private val parsed = object : LinkedHashMap<String, Pair<String, ParsedResource>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ParsedResource>>) = size > 4000
    }

    private fun parse(r: ResourceEntity, z: ZoneId): ParsedResource? = synchronized(parsed) {
        val key = "${r.version}|${r.etag}|${r.ics.length}|${r.ics.hashCode()}|${z.id}"
        parsed[r.id]?.takeIf { it.first == key }?.second ?: ParsedResource.parseOrNull(r.ics, z)?.also { parsed[r.id] = key to it }
    }

    suspend fun calendars(): List<CalendarInfo> {
        val s = settings()
        val stored = store.calendars().map { c ->
            CalendarInfo(c.id, c.name, c.color, c.readOnly, c.shown, if (c.local) LOCAL_GROUP else SERVER_GROUP)
        }
        val holidays = HolidayCalendar.entries.map { CalendarInfo(it.id, it.title, s.holidayColors[it.id] ?: it.color, true, it.id in s.holidays, HOLIDAY_GROUP) }
        return stored + holidays
    }

    /**
     * A calendar's colour, picked here: a server calendar's is written to the server at the next
     * sync (CalDAV PROPPATCH), the phone's own and the holiday calendars' stay here.
     */
    suspend fun setColor(id: String, color: Int, settingsStore: SettingsStore) {
        if (HolidayCalendar.of(id) != null) {
            settingsStore.update { it.copy(holidayColors = it.holidayColors + (id to color)) }
        } else {
            val c = store.calendar(id) ?: return
            store.putCalendar(c.copy(color = color, colorState = if (c.local) CalendarEntity.COLOR_SERVER else CalendarEntity.COLOR_TO_SEND, lastError = null))
        }
        changed()
    }

    suspend fun setShown(id: String, shown: Boolean, settingsStore: SettingsStore) {
        if (HolidayCalendar.of(id) != null) {
            settingsStore.update { it.copy(holidays = if (shown) it.holidays + id else it.holidays - id) }
        } else {
            store.calendar(id)?.let { store.putCalendar(it.copy(shown = shown)) }
        }
        changed()
    }

    /** The phone's own calendar, made on first use. */
    suspend fun localCalendar(): CalendarEntity =
        store.calendars().firstOrNull { it.local } ?: CalendarEntity("local:" + UUID.randomUUID(), CalendarEntity.LOCAL, "Phone", 0xFF308FFE.toInt(), sortKey = "000").also { store.putCalendar(it) }

    /** Every date from [from] to [to] (inclusive) of the shown calendars, holidays included. */
    suspend fun occurrences(from: LocalDate, to: LocalDate, onlyShown: Boolean = true): List<Occurrence> = withContext(work) {
        val z = zone()
        val cals = store.calendars().filter { !onlyShown || it.shown }.map { it.id }
        val lo = from.atStartOfDay(z).toInstant().toEpochMilli() - DAY
        val hi = to.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() + DAY
        val rows = store.inRange(cals, lo, hi)
        val budgets = HashMap<String, Recurrence.Budget>()
        val out = ArrayList<Occurrence>()
        for (r in rows) {
            val res = parse(r, z) ?: continue
            val budget = budgets.getOrPut(r.calendarId) { Recurrence.budget(from, to) }
            out += Occurrences.expand(res, r.id, r.calendarId, from, to, z, budget)
        }
        val s = settings()
        for (h in HolidayCalendar.entries) {
            if (onlyShown && h.id !in s.holidays) continue
            for (d in Holidays.between(h, from, to)) out += holiday(h, d, z).let { o -> s.holidayColors[h.id]?.let { o.copy(color = it) } ?: o }
        }
        out.sortWith(compareBy<Occurrence>({ it.firstDay }, { !it.allDay }, { it.start }, { it.title }))
        out
    }

    private fun holiday(h: HolidayCalendar, d: com.xern.xrncal.domain.Holiday, z: ZoneId) = Occurrence(
        resourceId = "${h.id}|${d.date}", calendarId = h.id, uid = "${h.id}-${d.date}", recurrenceId = null,
        title = d.title, location = "", notes = d.notes, allDay = true,
        start = d.date.atStartOfDay(z).toInstant(), end = d.date.plusDays(1).atStartOfDay(z).toInstant(),
        firstDay = d.date, lastDay = d.date, recurring = false, exception = false, reminders = emptyList(), color = d.color,
    )

    /** The occurrence a reminder or a search opens: [resourceId]'s date starting at [start], or its nearest. */
    suspend fun find(resourceId: String, start: Long?): Occurrence? = withContext(work) {
        val r = store.resource(resourceId) ?: return@withContext null
        val z = zone()
        val res = parse(r, z) ?: return@withContext null
        if (start != null) {
            val day = Instant.ofEpochMilli(start).atZone(z).toLocalDate()
            Occurrences.expand(res, r.id, r.calendarId, day.minusDays(1), day.plusDays(1), z).firstOrNull { it.start.toEpochMilli() == start }?.let { return@withContext it }
        }
        Occurrences.nearest(res, r.id, r.calendarId, clock(), z)
    }

    suspend fun resource(id: String): ResourceEntity? = store.resource(id)

    suspend fun parsed(id: String): ParsedResource? = store.resource(id)?.let { parse(it, zone()) }

    /** The editor's draft for an occurrence: this date as it is, or ([Scope.ALL]) the series. */
    suspend fun draft(occ: Occurrence, scope: Scope): EventDraft? = withContext(work) {
        val res = parsed(occ.resourceId) ?: return@withContext null
        if (scope == Scope.ALL && occ.recurrenceId != null) Edits.seriesDraft(res, occ, zone()) else Edits.draftOf(res, occ, zone())
    }

    suspend fun create(calendarId: String, draft: EventDraft): String = withContext(work) { createHere(calendarId, draft) }

    private suspend fun createHere(calendarId: String, draft: EventDraft): String {
        val cal = store.calendar(calendarId) ?: error("no calendar $calendarId")
        require(!cal.readOnly) { "read-only calendar" }
        val uid = UUID.randomUUID().toString()
        val r = store.saveLocal(ResourceEntity(ResourceEntity.newId(), calendarId, ics = Edits.create(draft, uid, clock())))
        changed()
        return r.id
    }

    /**
     * Saves an edit. A different [calendarId] moves the event: the whole resource is written into
     * the new calendar and deleted from the old one (on the server too, at the next sync).
     */
    suspend fun update(occ: Occurrence, draft: EventDraft, scope: Scope, calendarId: String = occ.calendarId): String =
        withContext(work) { updateHere(occ, draft, scope, calendarId) }

    private suspend fun updateHere(occ: Occurrence, draft: EventDraft, scope: Scope, calendarId: String): String {
        val r = store.resource(occ.resourceId) ?: error("gone")
        val from = store.calendar(r.calendarId) ?: error("no calendar")
        require(!from.readOnly) { "read-only calendar" }
        val ics = Edits.update(r.ics, occ, draft, scope, clock())
        if (calendarId == r.calendarId) {
            store.saveLocal(r.copy(ics = ics))
            changed()
            return r.id
        }
        val to = store.calendar(calendarId) ?: error("no calendar $calendarId")
        require(!to.readOnly) { "read-only calendar" }
        val moved = store.saveLocal(ResourceEntity(ResourceEntity.newId(), calendarId, ics = ics))
        store.deleteLocal(r.id)
        changed()
        return moved.id
    }

    /** A resource as it was before a delete, for Undo. */
    data class Undo(val before: ResourceEntity)

    /** Deletes [occ] (that date, or the whole event); returns what Undo needs to put it back. */
    suspend fun delete(occ: Occurrence, scope: Scope): Undo? = withContext(work) {
        val r = store.resource(occ.resourceId) ?: return@withContext null
        val cal = store.calendar(r.calendarId) ?: return@withContext null
        require(!cal.readOnly) { "read-only calendar" }
        val ics = Edits.delete(r.ics, occ, scope, clock())
        if (ics == null) store.deleteLocal(r.id) else store.saveLocal(r.copy(ics = ics))
        changed()
        Undo(r)
    }

    /**
     * Puts a deleted event back as it was, as the laptop's Undo does: its resource again if the
     * delete is still waiting to go to the server, else made again (with the same UID) if it has
     * gone there or was never sent. Returns the resource's id.
     */
    suspend fun undo(u: Undo): String = withContext(work) {
        val now = store.resource(u.before.id)
        val id = if (now != null) {
            store.saveLocal(now.copy(ics = u.before.ics, deleted = false)).id
        } else {
            store.saveLocal(ResourceEntity(ResourceEntity.newId(), u.before.calendarId, ics = u.before.ics)).id
        }
        changed()
        id
    }

    /** The iCalendar text to share for [occ]: its resource, or a holiday written as an event. */
    suspend fun shareText(occ: Occurrence): String = withContext(work) {
        store.resource(occ.resourceId)?.ics ?: Edits.create(
            EventDraft(occ.title, occ.allDay, occ.firstDay, java.time.LocalTime.MIDNIGHT, occ.lastDay, java.time.LocalTime.MIDNIGHT, zone(), notes = occ.notes),
            occ.uid, clock(),
        )
    }

    /** Events whose title, place or notes have every word typed, each at its date nearest to now. */
    suspend fun search(query: String): List<Occurrence> = withContext(work) {
        val words = TextFold.forMatch(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return@withContext emptyList()
        val z = zone()
        val cals = store.calendars().map { it.id }
        val rows = store.matching(cals, words.maxBy { it.length }).filter { r -> TextFold.matches(r.search, query) }
        val found = rows.mapNotNull { r -> parse(r, z)?.let { Occurrences.nearest(it, r.id, r.calendarId, clock(), z) } }
        val now = clock()
        // What is to come first, soonest first; then the past, latest first.
        found.sortedWith(compareBy<Occurrence>({ it.end.isBefore(now) }, { if (it.end.isBefore(now)) -it.start.toEpochMilli() else it.start.toEpochMilli() }))
    }

    /** What the editor's suggestions learn from: the titles and the places of the events already there. */
    data class History(val titles: List<com.xern.xrncal.domain.TitleSample>, val places: List<com.xern.xrncal.domain.LocationSample>)

    /**
     * Every event of the last two years (the laptop's look-back for places; titles look back one),
     * as the suggestion rankings take them: an all-day event's days as floating dates, as the
     * laptop stores them.
     */
    suspend fun history(): History = withContext(work) {
        val z = zone()
        val now = clock()
        val since = now.minus(java.time.Duration.ofDays(com.xern.xrncal.domain.LocationSuggestions.LOOKBACK_DAYS)).toEpochMilli()
        val titles = ArrayList<com.xern.xrncal.domain.TitleSample>()
        val places = ArrayList<com.xern.xrncal.domain.LocationSample>()
        for (r in store.live(store.calendars().map { it.id })) {
            if (r.rangeEnd < since) continue
            val res = parse(r, z) ?: continue
            for (e in res.masters) {
                val s = e.startInstant ?: continue
                val first = e.start!!.date
                val start = if (e.allDay) com.xern.xrncal.domain.TitleSuggestions.floatingDay(first) else s
                val end = if (e.allDay) com.xern.xrncal.domain.TitleSuggestions.floatingDay(first.plusDays(e.extraDays)) else s.plus(e.length)
                if (e.title.isNotBlank()) titles += com.xern.xrncal.domain.TitleSample(e.title, r.calendarId, start, end, e.allDay, e.location.ifBlank { null }, e.rrule)
                if (e.location.isNotBlank()) places += com.xern.xrncal.domain.LocationSample(e.location, s, e.rrule)
            }
        }
        History(titles, places)
    }

    suspend fun problems(): List<Problem> {
        val names = store.calendars().associate { it.id to it.name }
        return store.withProblems().map { Problem(it.id, it.title.ifBlank { "(no title)" }, names[it.calendarId].orEmpty(), it.problem!!) }
    }

    suspend fun keepMine(id: String) {
        store.keepMine(id)
        changed()
    }

    companion object {
        private const val DAY = 86_400_000L
        const val SERVER_GROUP = "server"
        const val LOCAL_GROUP = "local"
        const val HOLIDAY_GROUP = "holidays"
    }
}
