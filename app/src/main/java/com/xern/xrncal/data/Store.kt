package com.xern.xrncal.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import com.xern.xrncal.domain.ResourceIndex
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A calendar: a CalDAV collection (its URL is its id) or the phone's own. */
@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey val id: String,
    /** [CALDAV] or [LOCAL]. */
    val account: String,
    val name: String,
    val color: Int,
    val readOnly: Boolean = false,
    /** The drawer's switch. */
    val shown: Boolean = true,
    val ctag: String? = null,
    /** The last sync-token; [NO_SYNC_COLLECTION] when the server can't do sync-collection. */
    val syncToken: String? = null,
    val sortKey: String = "",
    val lastError: String? = null,
) {
    val local: Boolean get() = account == LOCAL

    companion object {
        const val CALDAV = "caldav"
        const val LOCAL = "local"
        const val NO_SYNC_COLLECTION = "-"
    }
}

/**
 * One calendar object resource: its text, where the server keeps it, and what the screens and the
 * sync need to know of it without reading the text.
 */
@Entity(
    tableName = "resources",
    indices = [Index("calendarId"), Index("hrefKey"), Index("dirty"), Index("calendarId", "rangeStart", "rangeEnd")],
)
data class ResourceEntity(
    @PrimaryKey val id: String,
    val calendarId: String,
    /** Where the server has it, as the server lists it (or as it was sent); null until it is. */
    val href: String? = null,
    /** [href]'s path, percent-escapes undone, which is what two spellings of one address share. */
    val hrefKey: String? = null,
    val etag: String? = null,
    val ics: String,
    val uid: String = "",
    val title: String = "",
    val rangeStart: Long = Long.MIN_VALUE,
    val rangeEnd: Long = Long.MAX_VALUE,
    val recurring: Boolean = false,
    val search: String = "",
    /** Changed here and not yet on the server. */
    val dirty: Boolean = false,
    /** Deleted here; the server's copy is still to be deleted. */
    val deleted: Boolean = false,
    /** Why it can't be sent: [CONFLICT], [GONE], or what the server refused it with. */
    val problem: String? = null,
    /** The server's version when [problem] is a conflict. */
    val serverIcs: String? = null,
    val serverEtag: String? = null,
    /** The owner chose this version over the server's: sent without a precondition. */
    val keepMine: Boolean = false,
    /** Counts changes made here, so a push only marks done what it actually sent. */
    val version: Long = 0,
) {
    companion object {
        const val CONFLICT = "conflict"
        const val GONE = "gone"

        fun keyOf(href: String?): String? = href?.let {
            val path = runCatching { URI(it).rawPath }.getOrNull() ?: it
            runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        }

        /** [r] with the columns worked out from its text again. */
        fun indexed(r: ResourceEntity): ResourceEntity {
            val i = ResourceIndex.of(r.ics)
            return r.copy(uid = i.uid, title = i.title, rangeStart = i.rangeStart, rangeEnd = i.rangeEnd, recurring = i.recurring, search = i.search)
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY account, sortKey, name")
    suspend fun all(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun get(id: String): CalendarEntity?

    @Upsert
    suspend fun put(c: CalendarEntity)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ResourceDao {
    @Query("SELECT * FROM resources WHERE id = :id")
    suspend fun get(id: String): ResourceEntity?

    @Query("SELECT * FROM resources WHERE calendarId = :calendarId AND hrefKey = :key LIMIT 1")
    suspend fun byHrefKey(calendarId: String, key: String): ResourceEntity?

    @Query("SELECT * FROM resources WHERE calendarId = :calendarId AND uid = :uid AND uid != '' ORDER BY dirty LIMIT 1")
    suspend fun byUid(calendarId: String, uid: String): ResourceEntity?

    @Query("SELECT * FROM resources WHERE calendarId = :calendarId")
    suspend fun inCalendar(calendarId: String): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE calendarId = :calendarId AND dirty = 1")
    suspend fun dirty(calendarId: String): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE deleted = 0 AND calendarId IN (:calendars) AND rangeStart < :to AND rangeEnd > :from")
    suspend fun inRange(calendars: List<String>, from: Long, to: Long): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE deleted = 0 AND calendarId IN (:calendars) AND search LIKE '%' || :word || '%'")
    suspend fun matching(calendars: List<String>, word: String): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE problem IS NOT NULL")
    suspend fun withProblems(): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE deleted = 0 AND calendarId IN (:calendars)")
    suspend fun live(calendars: List<String>): List<ResourceEntity>

    @Upsert
    suspend fun put(r: ResourceEntity)

    @Query("DELETE FROM resources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM resources WHERE calendarId = :calendarId")
    suspend fun deleteCalendar(calendarId: String)
}

@Database(entities = [CalendarEntity::class, ResourceEntity::class], version = 1, exportSchema = true)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendars(): CalendarDao
    abstract fun resources(): ResourceDao

    companion object {
        fun open(context: Context): CalendarDatabase = Room.databaseBuilder(context, CalendarDatabase::class.java, "calendar.db").build()
    }
}

/**
 * Where calendars and resources are kept: Room on the phone, memory in the tests. The changes the
 * sync makes check, in the same step, what the owner did meanwhile: a push marks done only the
 * version it sent, and a pull never writes over a change not yet sent.
 */
abstract class CalendarStore {
    private val _revision = MutableStateFlow(0L)

    /** Moves on with every change, so the screens read again. */
    val revision: StateFlow<Long> = _revision

    protected fun changed() {
        _revision.value++
    }

    abstract suspend fun calendars(): List<CalendarEntity>
    abstract suspend fun calendar(id: String): CalendarEntity?
    abstract suspend fun putCalendar(c: CalendarEntity)

    /** A calendar gone, with everything in it. */
    abstract suspend fun removeCalendar(id: String)

    abstract suspend fun resource(id: String): ResourceEntity?
    abstract suspend fun inCalendar(calendarId: String): List<ResourceEntity>
    abstract suspend fun dirty(calendarId: String): List<ResourceEntity>
    abstract suspend fun inRange(calendars: List<String>, from: Long, to: Long): List<ResourceEntity>
    abstract suspend fun matching(calendars: List<String>, word: String): List<ResourceEntity>
    abstract suspend fun live(calendars: List<String>): List<ResourceEntity>
    abstract suspend fun withProblems(): List<ResourceEntity>

    protected abstract suspend fun <T> atomically(block: suspend Ops.() -> T): T

    /** The few reads and writes a step is made of. */
    protected interface Ops {
        suspend fun get(id: String): ResourceEntity?
        suspend fun byHrefKey(calendarId: String, key: String): ResourceEntity?
        suspend fun byUid(calendarId: String, uid: String): ResourceEntity?
        suspend fun put(r: ResourceEntity)
        suspend fun delete(id: String)
    }

    /** A change made here: kept, and marked to be sent. */
    suspend fun saveLocal(r: ResourceEntity): ResourceEntity = atomically {
        val before = get(r.id)
        val next = ResourceEntity.indexed(r).copy(dirty = true, version = (before?.version ?: r.version) + 1)
        put(next)
        next
    }.also { changed() }

    /** Deleted here: a resource never sent just goes; one on the server waits to be deleted there. */
    suspend fun deleteLocal(id: String) {
        atomically {
            val r = get(id) ?: return@atomically
            if (r.href == null) delete(id) else put(r.copy(deleted = true, dirty = true, version = r.version + 1))
        }
        changed()
    }

    /** A PUT went through: done, if nothing changed here since [sent]. */
    suspend fun pushed(sent: ResourceEntity, href: String, etag: String?) {
        atomically {
            val now = get(sent.id) ?: return@atomically
            val same = now.version == sent.version
            put(now.copy(href = href, hrefKey = ResourceEntity.keyOf(href), etag = etag, dirty = !same, problem = if (same) null else now.problem, serverIcs = if (same) null else now.serverIcs, keepMine = if (same) false else now.keepMine))
        }
        changed()
    }

    /** A DELETE went through (or the server had nothing there). */
    suspend fun pushedDelete(sent: ResourceEntity) {
        atomically {
            val now = get(sent.id) ?: return@atomically
            if (now.deleted) delete(sent.id)
        }
        changed()
    }

    /** The server refused a change, or someone else changed it first: kept here, and shown. */
    suspend fun flag(id: String, problem: String, serverIcs: String? = null, serverEtag: String? = null) {
        atomically {
            val now = get(id) ?: return@atomically
            put(now.copy(problem = problem, serverIcs = serverIcs ?: now.serverIcs, serverEtag = serverEtag ?: now.serverEtag, keepMine = false))
        }
        changed()
    }

    /**
     * The server's copy of [href] in [calendarId]: taken, unless the resource was changed here and
     * not yet sent (the push decides about that one). Returns whether it was taken.
     */
    suspend fun applyServer(calendarId: String, href: String, etag: String?, ics: String): Boolean {
        val key = ResourceEntity.keyOf(href)!!
        val fresh = ResourceEntity.indexed(ResourceEntity(ResourceEntity.newId(), calendarId, href, key, etag, ics))
        val taken = atomically {
            val known = byHrefKey(calendarId, key) ?: byUid(calendarId, fresh.uid)?.takeIf { it.href == null || it.hrefKey == key }
            when {
                known == null -> {
                    put(fresh)
                    true
                }
                known.dirty || known.problem != null -> false
                else -> {
                    put(fresh.copy(id = known.id, version = known.version))
                    true
                }
            }
        }
        if (taken) changed()
        return taken
    }

    /** [href] is gone from the server: gone here too, unless it was changed here (then a conflict). */
    suspend fun removeGone(calendarId: String, href: String) {
        atomically {
            val known = byHrefKey(calendarId, ResourceEntity.keyOf(href)!!) ?: return@atomically
            when {
                known.deleted -> delete(known.id)
                known.dirty -> put(known.copy(problem = ResourceEntity.GONE, serverIcs = null, serverEtag = null))
                else -> delete(known.id)
            }
        }
        changed()
    }

    /** "Keep mine": sent again without a precondition (or made again, for one gone from the server). */
    suspend fun keepMine(id: String) {
        atomically {
            val r = get(id) ?: return@atomically
            val gone = r.problem == ResourceEntity.GONE
            put(r.copy(problem = null, keepMine = !gone, dirty = true, href = if (gone) null else r.href, hrefKey = if (gone) null else r.hrefKey, etag = if (gone) null else r.etag, version = r.version + 1))
        }
        changed()
    }

    /** "Keep theirs": the server's copy (or nothing, for one gone from the server or deleted here). */
    suspend fun keepTheirs(id: String, ics: String?, etag: String?) {
        atomically {
            val r = get(id) ?: return@atomically
            if (ics == null || r.problem == ResourceEntity.GONE) delete(id)
            else put(ResourceEntity.indexed(r.copy(ics = ics, etag = etag, dirty = false, deleted = false, problem = null, serverIcs = null, serverEtag = null, keepMine = false, version = r.version + 1)))
        }
        changed()
    }

    /** A resource made here by the sync, as it is (for tests and the local calendar's first start). */
    suspend fun putRaw(r: ResourceEntity) {
        atomically { put(r) }
        changed()
    }
}

class RoomCalendarStore(private val db: CalendarDatabase) : CalendarStore() {
    private val cal = db.calendars()
    private val res = db.resources()

    override suspend fun calendars() = cal.all()
    override suspend fun calendar(id: String) = cal.get(id)
    override suspend fun putCalendar(c: CalendarEntity) {
        cal.put(c)
        changed()
    }

    override suspend fun removeCalendar(id: String) {
        db.withTransaction {
            res.deleteCalendar(id)
            cal.delete(id)
        }
        changed()
    }

    override suspend fun resource(id: String) = res.get(id)
    override suspend fun inCalendar(calendarId: String) = res.inCalendar(calendarId)
    override suspend fun dirty(calendarId: String) = res.dirty(calendarId)
    override suspend fun inRange(calendars: List<String>, from: Long, to: Long) = if (calendars.isEmpty()) emptyList() else res.inRange(calendars, from, to)
    override suspend fun matching(calendars: List<String>, word: String) = if (calendars.isEmpty()) emptyList() else res.matching(calendars, word)
    override suspend fun live(calendars: List<String>) = if (calendars.isEmpty()) emptyList() else res.live(calendars)
    override suspend fun withProblems() = res.withProblems()

    private val ops = object : Ops {
        override suspend fun get(id: String) = res.get(id)
        override suspend fun byHrefKey(calendarId: String, key: String) = res.byHrefKey(calendarId, key)
        override suspend fun byUid(calendarId: String, uid: String) = res.byUid(calendarId, uid)
        override suspend fun put(r: ResourceEntity) = res.put(r)
        override suspend fun delete(id: String) = res.delete(id)
    }

    override suspend fun <T> atomically(block: suspend Ops.() -> T): T = db.withTransaction { ops.block() }
}

/** The store in memory, for the JVM tests. */
class MemoryCalendarStore : CalendarStore() {
    private val mutex = Mutex()
    private val cals = LinkedHashMap<String, CalendarEntity>()
    private val rows = LinkedHashMap<String, ResourceEntity>()

    override suspend fun calendars() = mutex.withLock { cals.values.sortedWith(compareBy({ it.account }, { it.sortKey }, { it.name })) }
    override suspend fun calendar(id: String) = mutex.withLock { cals[id] }
    override suspend fun putCalendar(c: CalendarEntity) {
        mutex.withLock { cals[c.id] = c }
        changed()
    }

    override suspend fun removeCalendar(id: String) {
        mutex.withLock {
            cals.remove(id)
            rows.values.removeAll { it.calendarId == id }
        }
        changed()
    }

    override suspend fun resource(id: String) = mutex.withLock { rows[id] }
    override suspend fun inCalendar(calendarId: String) = mutex.withLock { rows.values.filter { it.calendarId == calendarId } }
    override suspend fun dirty(calendarId: String) = mutex.withLock { rows.values.filter { it.calendarId == calendarId && it.dirty } }
    override suspend fun inRange(calendars: List<String>, from: Long, to: Long) =
        mutex.withLock { rows.values.filter { !it.deleted && it.calendarId in calendars && it.rangeStart < to && it.rangeEnd > from } }
    override suspend fun matching(calendars: List<String>, word: String) =
        mutex.withLock { rows.values.filter { !it.deleted && it.calendarId in calendars && it.search.contains(word) } }
    override suspend fun live(calendars: List<String>) = mutex.withLock { rows.values.filter { !it.deleted && it.calendarId in calendars } }
    override suspend fun withProblems() = mutex.withLock { rows.values.filter { it.problem != null } }

    private val ops = object : Ops {
        override suspend fun get(id: String) = rows[id]
        override suspend fun byHrefKey(calendarId: String, key: String) = rows.values.firstOrNull { it.calendarId == calendarId && it.hrefKey == key }
        override suspend fun byUid(calendarId: String, uid: String) = rows.values.filter { it.calendarId == calendarId && it.uid == uid && uid.isNotEmpty() }.minByOrNull { if (it.dirty) 1 else 0 }
        override suspend fun put(r: ResourceEntity) {
            rows[r.id] = r
        }
        override suspend fun delete(id: String) {
            rows.remove(id)
        }
    }

    override suspend fun <T> atomically(block: suspend Ops.() -> T): T = mutex.withLock { ops.block() }

    /** Every row, for the tests. */
    suspend fun everything(): List<ResourceEntity> = mutex.withLock { rows.values.toList() }
}
