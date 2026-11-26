package com.xern.xrncal

import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.CalDav
import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.DavCalendar
import com.xern.xrncal.data.DavDelta
import com.xern.xrncal.data.DavItem
import com.xern.xrncal.data.MemoryAccount
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.MemoryRungLog
import com.xern.xrncal.data.MemorySettings
import com.xern.xrncal.data.ResourceEntity
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.Scope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changes made on the phone while a sync is sending (docs/spec.md, "Sync" 7): the sync running
 * finishes, and another one sends what came after. The server is a stand-in that can hold a PUT
 * or a DELETE until the test lets it through, the way a slow connection does.
 */
class SyncWhileEditingTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-11-24T02:00:00Z")
    private val home = "https://dav.example/alex/"
    private val work = home + "work/"
    private val href = work + "dentist-1.ics"

    private class Server : CalDav {
        val items = ConcurrentHashMap<String, Pair<String, String>>()
        private val n = AtomicInteger(1)

        /** The next PUT or DELETE waits for this, once it has said so on [started]. */
        @Volatile var hold: CountDownLatch? = null
        val started = Semaphore(0)

        /** The next PUT is stored, and its answer lost on the way back. */
        @Volatile var loseAnswer = false

        private fun waitIfHeld() {
            val h = hold ?: return
            hold = null
            started.release()
            h.await(20, TimeUnit.SECONDS)
        }

        override fun findHome() = error("not asked")
        override fun calendars(home: String) = listOf(DavCalendar(home + "work/", "Work", null, null, false))
        override fun syncCollection(calendar: String, token: String?) = DavDelta("t" + n.get(), emptyList(), emptyList())
        override fun etags(calendar: String) = items.map { DavItem(it.key, it.value.first) }
        override fun multiget(calendar: String, hrefs: List<String>) = hrefs.mapNotNull(::get)
        override fun get(href: String) = items[href]?.let { DavItem(href, it.first, it.second) }
        override fun setColor(calendar: String, color: String) = 200

        override fun put(href: String, ics: String, etag: String?, create: Boolean): Pair<Int, String?> {
            waitIfHeld()
            val there = items[href]
            if (create && there != null) return 412 to null
            if (etag != null && there?.first != etag) return 412 to null
            val e = "\"e${n.incrementAndGet()}\""
            items[href] = e to ics
            if (loseAnswer) {
                loseAnswer = false
                throw com.xern.xrncal.data.DavFailure(0, "connection reset")
            }
            return 201 to e
        }

        override fun delete(href: String, etag: String?): Int {
            waitIfHeld()
            val there = items[href] ?: return 404
            if (etag != null && there.first != etag) return 412
            items.remove(href)
            return 204
        }
    }

    private val server = Server()
    private val store = MemoryCalendarStore()
    private val account = MemoryAccount().also {
        it.address = home
        it.user = "alex"
        it.password = "made-up-password-1"
        it.home = home
    }
    private val graph = Graph(
        store = store,
        settings = MemorySettings(),
        account = account,
        alarms = object : AlarmScheduler {
            override fun set(at: Long) = Unit
            override fun cancel() = Unit
        },
        rungLog = MemoryRungLog(),
        zone = { zone },
        clock = { now },
        client = { server },
    )

    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Laptop//EN\r\nBEGIN:VEVENT\r\nUID:dentist-1\r\nDTSTAMP:20261101T000000Z\r\n" +
        "SUMMARY:Dentist\r\nDTSTART;TZID=Asia/Ho_Chi_Minh:20261126T090000\r\nDTEND;TZID=Asia/Ho_Chi_Minh:20261126T100000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    /** One event, on the server and on the phone, in sync. */
    private fun synced() = runBlocking {
        server.items[href] = "\"e1\"" to ics
        store.putCalendar(CalendarEntity(work, CalendarEntity.CALDAV, "Work", 0, syncToken = "t1"))
        store.putRaw(ResourceEntity.indexed(ResourceEntity("dentist-row", work, href, ResourceEntity.keyOf(href), "\"e1\"", ics)))
    }

    private fun occurrence(title: String) = runBlocking { graph.events.occurrences(LocalDate.parse("2026-11-26"), LocalDate.parse("2026-11-26")).single { it.title == title } }

    private fun draft(title: String) = EventDraft(title, false, LocalDate.parse("2026-11-26"), LocalTime.parse("09:00"), LocalDate.parse("2026-11-26"), LocalTime.parse("10:00"), zone)

    /** Waits for [done], up to 15 seconds (each sync waits 2 seconds after a change). */
    private fun eventually(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    private fun rows() = runBlocking { store.everything() }

    @Test
    fun anEditWhileTheLastOneIsBeingSentLetsThatSyncFinishAndSendsItAfter() {
        synced()
        server.hold = CountDownLatch(1)
        val held = server.hold!!
        runBlocking { graph.events.update(occurrence("Dentist"), draft("Dentist (first)"), Scope.ALL) }
        assertTrue("the first PUT started", server.started.tryAcquire(10, TimeUnit.SECONDS))
        // The second edit comes while the first is on its way.
        runBlocking { graph.events.update(occurrence("Dentist (first)"), draft("Dentist (second)"), Scope.ALL) }
        held.countDown()
        eventually { rows().none { it.dirty } || rows().any { it.problem != null } }
        assertEquals(emptyList<String>(), rows().mapNotNull { it.problem })
        assertTrue(server.items.getValue(href).second.contains("SUMMARY:Dentist (second)"))
        assertTrue(rows().none { it.dirty })
    }
}
