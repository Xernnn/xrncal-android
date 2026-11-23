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

    suspend fun delete(occ: Occurrence, scope: Scope) = withContext(work) {
        val r = store.resource(occ.resourceId) ?: return@withContext
        val cal = store.calendar(r.calendarId) ?: return@withContext
        require(!cal.readOnly) { "read-only calendar" }
        val ics = Edits.delete(r.ics, occ, scope, clock())
        if (ics == null) store.deleteLocal(r.id) else store.saveLocal(r.copy(ics = ics))
        changed()
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
