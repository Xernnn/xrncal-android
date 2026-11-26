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
import com.xern.xrncal.data.Ring
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminder alarm going off (docs/spec.md, "Reminders"): a sync first, so an event moved on the
 * laptop doesn't ring, but never waited for longer than a few seconds. The server here hangs the
 * way a dead connection does: the call blocks and nothing can interrupt it.
 */
class ReminderRingTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    // 08:45 on the 26th, 15 minutes before the dentist.
    private val now = Instant.parse("2026-11-26T01:45:00Z")
    private val home = "https://dav.example/alex/"
    private val work = home + "work/"
    private val hung = CountDownLatch(1)

    private val server = object : CalDav {
        override fun findHome() = home
        override fun calendars(home: String): List<DavCalendar> {
            hung.await(10, TimeUnit.SECONDS)
            return listOf(DavCalendar(work, "Work", null, "c1", false))
        }
        override fun syncCollection(calendar: String, token: String?) = DavDelta("t2", emptyList(), emptyList())
        override fun etags(calendar: String) = emptyList<DavItem>()
        override fun multiget(calendar: String, hrefs: List<String>) = emptyList<DavItem>()
        override fun get(href: String): DavItem? = null
        override fun put(href: String, ics: String, etag: String?, create: Boolean) = 201 to "\"e2\""
        override fun delete(href: String, etag: String?) = 204
        override fun setColor(calendar: String, color: String) = 200
    }

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

    @Test
    fun aReminderRingsWithinItsWaitWhileTheServerHangsAndTheSyncGoesOn() = runBlocking {
        store.putCalendar(CalendarEntity(work, CalendarEntity.CALDAV, "Work", 0, ctag = "c1", syncToken = "t1"))
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Laptop//EN\r\nBEGIN:VEVENT\r\nUID:dentist-1\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Dentist\r\n" +
            "DTSTART;TZID=Asia/Ho_Chi_Minh:20261126T090000\r\nDTEND;TZID=Asia/Ho_Chi_Minh:20261126T100000\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        store.putRaw(ResourceEntity.indexed(ResourceEntity("dentist-row", work, work + "dentist-1.ics", ResourceEntity.keyOf(work + "dentist-1.ics"), "\"e1\"", ics)))
        val shown = mutableListOf<Ring>()
        val t0 = System.nanoTime()
        graph.ring(waitMs = 500) { shown += it }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue("rang after $took ms", took < 3_000)
        assertEquals(listOf("Dentist"), shown.map { it.occurrence.title })
        // The sync wasn't stopped: once the server answers it finishes.
        hung.countDown()
        val until = System.currentTimeMillis() + 10_000
        while (account.lastSync == 0L && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(now.toEpochMilli(), account.lastSync)
    }
}
