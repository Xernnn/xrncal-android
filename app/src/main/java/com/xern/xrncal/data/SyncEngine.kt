package com.xern.xrncal.data

import com.xern.xrncal.domain.Palette
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The CalDAV login, and what the sync remembers of the account. */
interface Account {
    var address: String
    var user: String
    var password: String
    /** The calendar home discovery found; null until it has. */
    var home: String?
    var lastSync: Long
    var lastError: String

    val connected: Boolean get() = address.isNotBlank() && user.isNotBlank()
}

class MemoryAccount : Account {
    override var address = ""
    override var user = ""
    override var password = ""
    override var home: String? = null
    override var lastSync = 0L
    override var lastError = ""
}

/** What a sync did, for the status line and the tests. */
data class SyncReport(val pulled: Int = 0, val pushed: Int = 0, val removed: Int = 0, val problems: Int = 0, val calendars: Int = 0)

/**
 * Brings the phone's calendars and the CalDAV server's together (docs/spec.md, "Sync"): the
 * calendar list, then for each calendar its changes made here (PUT and DELETE, conditional on the
 * ETag last seen), then what changed on the server (sync-collection with the stored token, or the
 * ETags listed), fetched with calendar-multiget. A 412 makes a conflict for the owner to settle;
 * nothing is sent again or written over until then.
 *
 * Every call runs on [io], whoever calls it: a screen's coroutine runs on the main thread, where
 * Android refuses the network with an exception that has no message.
 */
class SyncEngine(
    private val store: CalendarStore,
    private val account: Account,
    private val io: CoroutineContext = Dispatchers.IO,
    private val client: (Account) -> CalDav = { OkCalDav(it.address, it.user, it.password) },
) {
    private val mutex = Mutex()

    /**
     * Signs in: finds the calendar home from the address and lists its calendars, keeping both.
     * Throws [DavFailure] (a wrong password: 401) and IllegalArgumentException (not https).
     */
    suspend fun connect(): List<DavCalendar> = withContext(io) {
        mutex.withLock {
            val dav = client(account)
            val home = dav.findHome()
            account.home = home
            val list = dav.calendars(home)
            updateCalendars(list, signingIn = true)
            list
        }
    }

    suspend fun sync(): SyncReport = withContext(io) { mutex.withLock { syncHere() } }

    /** "Keep theirs": the server's copy now, for a resource in conflict. */
    suspend fun keepTheirs(id: String) = withContext(io) {
        mutex.withLock {
            val r = store.resource(id) ?: return@withLock
            val href = r.href
            val item = if (href != null && account.connected && r.problem != ResourceEntity.GONE) client(account).get(href) else null
            store.keepTheirs(id, item?.ics ?: r.serverIcs?.takeIf { href != null && r.problem != ResourceEntity.GONE }, item?.etag ?: r.serverEtag)
        }
    }

    private suspend fun syncHere(): SyncReport {
        if (!account.connected) return SyncReport()
        val dav = client(account)
        val home = account.home ?: dav.findHome().also { account.home = it }
        pushColors(dav)
        val listed = dav.calendars(home)
        updateCalendars(listed)
        var report = SyncReport(calendars = listed.size)
        for (d in listed) {
            val cal = store.calendar(d.href) ?: continue
            try {
                val pushed = push(dav, cal)
                report = report.copy(pushed = report.pushed + pushed.first, problems = report.problems + pushed.second)
                val known = cal.syncToken != null
                if (known && d.ctag != null && d.ctag == cal.ctag && pushed.first == 0) {
                    if (cal.lastError != null) store.putCalendar(cal.copy(lastError = null))
                    continue
                }
                val (pulled, removed) = pull(dav, cal, d.ctag)
                report = report.copy(pulled = report.pulled + pulled, removed = report.removed + removed)
            } catch (e: DavFailure) {
                if (e.status == 401 || e.status == 0) throw e
                store.calendar(cal.id)?.let { store.putCalendar(it.copy(lastError = e.text())) }
            }
        }
        return report
    }

    /**
     * Colours picked here, written to the server before the calendars are listed, so the listing
     * reads them back. A refusal keeps the colour here only, with the reason on the calendar.
     */
    private suspend fun pushColors(dav: CalDav) {
        for (c in store.calendars()) {
            if (c.account != CalendarEntity.CALDAV || c.colorState != CalendarEntity.COLOR_TO_SEND) continue
            val status = dav.setColor(c.id, Palette.calendarColor(c.color))
            val now = store.calendar(c.id) ?: continue
            // Picked again meanwhile: the next sync sends that one.
            if (now.color != c.color) continue
            store.putCalendar(
                if (status in 200..299) now.copy(colorState = CalendarEntity.COLOR_SERVER)
                else now.copy(colorState = CalendarEntity.COLOR_HERE, lastError = "Colour refused: $status"),
            )
        }
    }

    /**
     * The server's calendars here: new ones shown, names and colours as the server has them, gone
     * ones removed. A listing that would remove them all is an error, not a removal: an empty one,
     * or in a sync one naming none of them (signing in again may take another server's), since a
     * server that lost its folder or its rights answers that way too. A gone calendar holding a
     * change not yet sent, or one in conflict, stays until that is settled.
     */
    private suspend fun updateCalendars(listed: List<DavCalendar>, signingIn: Boolean = false) {
        val stored = store.calendars().filter { it.account == CalendarEntity.CALDAV }.associateBy { it.id }
        val keep = listed.map { it.href }.toSet()
        if (stored.isNotEmpty() && (keep.isEmpty() || (!signingIn && stored.keys.none { it in keep }))) {
            throw DavFailure(404, "the calendar list names none of the calendars")
        }
        listed.forEachIndexed { i, d ->
            val old = stored[d.href]
            // A colour picked here and not yet on the server (or refused by it) stays.
            val color = if (old != null && old.colorState != CalendarEntity.COLOR_SERVER) old.color else d.color ?: old?.color ?: Palette.pick(i)
            store.putCalendar(
                old?.copy(name = d.name, color = color, readOnly = d.readOnly)
                    ?: CalendarEntity(d.href, CalendarEntity.CALDAV, d.name, color, d.readOnly, sortKey = "%03d".format(i)),
            )
        }
        for (id in stored.keys - keep) {
            val unsent = store.inCalendar(id).any { (it.dirty && !it.deleted) || it.problem != null }
            when {
                !unsent -> store.removeCalendar(id)
                stored.getValue(id).lastError != GONE -> store.putCalendar(stored.getValue(id).copy(lastError = GONE))
            }
        }
    }

    /** Sends the calendar's changes: (sent, refused or in conflict). */
    private suspend fun push(dav: CalDav, cal: CalendarEntity): Pair<Int, Int> {
        var sent = 0
        var problems = 0
        for (r in store.dirty(cal.id)) {
            if (r.problem != null && !r.keepMine) continue
            if (cal.readOnly) {
                store.flag(r.id, "Read-only calendar")
                problems++
                continue
            }
            if (r.deleted) {
                val href = r.href
                if (href == null) {
                    store.pushedDelete(r)
                    continue
                }
                when (val status = dav.delete(href, if (r.keepMine) null else r.etag)) {
                    in 200..299, 404, 410 -> {
                        store.pushedDelete(r)
                        sent++
                    }
                    412 -> {
                        val theirs = dav.get(href)
                        if (theirs == null) store.pushedDelete(r) else store.flag(r.id, ResourceEntity.CONFLICT, theirs.ics, theirs.etag)
                        problems++
                    }
                    else -> {
                        if (status >= 500) throw DavFailure(status, "delete refused")
                        store.flag(r.id, "Refused: $status")
                        problems++
                    }
                }
                continue
            }
            val create = r.href == null
            val href = r.href ?: com.xern.xrncal.domain.ResourceNames.href(cal.id, r.uid.ifEmpty { r.id })
            val (status, etag) = dav.put(href, r.ics, if (r.keepMine) null else r.etag, create = create && !r.keepMine)
            when {
                status in 200..299 -> {
                    store.pushed(r, href, etag)
                    sent++
                }
                status == 412 -> {
                    val theirs = dav.get(href)
                    // A PUT whose answer was lost the first time (a dropped connection, a sync
                    // stopped): the server holds just what is sent, so it went through.
                    if (theirs?.ics?.let(::sameText) == sameText(r.ics)) {
                        store.pushed(r, href, theirs.etag)
                        sent++
                    } else {
                        store.flag(r.id, ResourceEntity.CONFLICT, theirs?.ics, theirs?.etag)
                        problems++
                    }
                }
                status >= 500 -> throw DavFailure(status, "PUT refused")
                else -> {
                    store.flag(r.id, "Refused: $status")
                    problems++
                }
            }
        }
        return sent to problems
    }

    private fun sameText(ics: String) = ics.replace("\r\n", "\n").trim()

    private companion object {
        /** A calendar kept here for its unsent changes after the server stopped listing it. */
        const val GONE = "Not on the server"
    }

    /** Reads what changed on the server: (taken, removed). */
    private suspend fun pull(dav: CalDav, cal: CalendarEntity, ctag: String?): Pair<Int, Int> {
        val stored = store.inCalendar(cal.id).filter { it.hrefKey != null }.associateBy { it.hrefKey!! }
        fun isNew(item: DavItem) = stored[ResourceEntity.keyOf(item.href)]?.let { it.etag == null || it.etag != item.etag } ?: true
        val canSync = cal.syncToken != CalendarEntity.NO_SYNC_COLLECTION
        // With the stored token; one the server no longer knows is dropped for a whole listing.
        var full = cal.syncToken == null
        var delta = if (canSync) dav.syncCollection(cal.id, cal.syncToken) else null
        if (delta == null && canSync && cal.syncToken != null) {
            delta = dav.syncCollection(cal.id, null)
            full = true
        }
        val changed: List<DavItem>
        val removed: List<String>
        if (delta != null) {
            changed = delta.changed.filter(::isNew)
            removed = if (full) {
                val listed = delta.changed.mapNotNull { ResourceEntity.keyOf(it.href) }.toSet()
                stored.values.filter { it.hrefKey !in listed }.mapNotNull { it.href } + delta.removed
            } else {
                delta.removed
            }
        } else {
            val listing = dav.etags(cal.id)
            val listed = listing.mapNotNull { ResourceEntity.keyOf(it.href) }.toSet()
            changed = listing.filter(::isNew)
            removed = stored.values.filter { it.hrefKey !in listed }.mapNotNull { it.href }
        }
        var taken = 0
        if (changed.isNotEmpty()) {
            for (item in dav.multiget(cal.id, changed.map { it.href })) {
                val ics = item.ics ?: continue
                // A resource with no event in it (a task in a mixed collection) isn't this app's to show.
                if (!ics.contains("BEGIN:VEVENT", ignoreCase = true)) continue
                if (store.applyServer(cal.id, item.href, item.etag, ics)) taken++
            }
        }
        for (href in removed) store.removeGone(cal.id, href)
        val token = when {
            delta != null -> delta.token
            else -> CalendarEntity.NO_SYNC_COLLECTION
        }
        val now = store.calendar(cal.id) ?: return taken to removed.size
        store.putCalendar(now.copy(ctag = ctag, syncToken = token, lastError = null))
        return taken to removed.size
    }
}
