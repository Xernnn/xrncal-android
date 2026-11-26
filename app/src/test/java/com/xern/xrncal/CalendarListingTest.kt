package com.xern.xrncal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.DavFailure
import com.xern.xrncal.data.MemoryAccount
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.ResourceEntity
import com.xern.xrncal.data.SyncEngine
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the calendar list's answer may remove (docs/spec.md, "Sync" 2): only a real listing removes
 * a calendar, never one that would remove them all, and never one holding a change not yet sent.
 * The server is a stand-in on a free port that answers the calendar home as each test says.
 */
class CalendarListingTest {
    private lateinit var server: HttpServer
    private val root get() = "http://127.0.0.1:${server.address.port}"
    private val homePath = "/alex/"

    /** The calendar home's answer: a status, and the collections a 207 lists. */
    @Volatile private var homeStatus = 207
    @Volatile private var listed = listOf("work", "trips", "home")
    private val puts = mutableListOf<String>()

    private val store = MemoryCalendarStore()
    private val account = MemoryAccount()
    private val engine = SyncEngine(store, account, Dispatchers.IO)

    private fun cal(name: String) = "$root$homePath$name/"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> answer(ex) }
        server.start()
        account.address = root + homePath
        account.user = "alex"
        account.password = "made-up-password-1"
        account.home = root + homePath
    }

    @After
    fun stop() = server.stop(0)

    private fun answer(ex: HttpExchange) {
        ex.requestBody.readBytes()
        val path = ex.requestURI.path
        val (status, body) = when {
            ex.requestMethod == "PROPFIND" && path == homePath -> homeStatus to (if (homeStatus == 207) listing() else "")
            ex.requestMethod == "REPORT" -> 207 to MULTISTATUS.format("<D:sync-token>t2</D:sync-token>")
            ex.requestMethod == "PUT" -> {
                synchronized(puts) { puts += path }
                ex.responseHeaders.add("ETag", "\"e2\"")
                201 to ""
            }
            else -> 404 to ""
        }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun listing(): String {
        val self = "<D:response><D:href>$homePath</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        val cals = listed.joinToString("") { name ->
            "<D:response><D:href>$homePath$name/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/><C:calendar/></D:resourcetype>" +
                "<D:displayname>$name</D:displayname><CS:getctag>c1</CS:getctag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        }
        return MULTISTATUS.format(self + cals)
    }

    /** Three calendars synced before, each with one event; "work"'s was edited offline and not yet sent. */
    private fun synced() = runBlocking {
        for (name in listOf("work", "trips", "home")) {
            store.putCalendar(CalendarEntity(cal(name), CalendarEntity.CALDAV, name, 0, ctag = "c1", syncToken = "t1"))
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Test//EN\r\nBEGIN:VEVENT\r\nUID:$name-1\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:$name event\r\nDTSTART:20261126T020000Z\r\nDTEND:20261126T030000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            store.putRaw(ResourceEntity.indexed(ResourceEntity("$name-row", cal(name), cal(name) + "$name-1.ics", ResourceEntity.keyOf(cal(name) + "$name-1.ics"), "\"e1\"", ics)))
        }
        val row = store.resource("work-row")!!
        store.saveLocal(row.copy(ics = row.ics.replace("SUMMARY:work event", "SUMMARY:work event (moved)")))
    }

    private fun calendars() = runBlocking { store.calendars().map { it.name }.sorted() }

    private fun workEdit() = runBlocking { store.resource("work-row") }

    private fun assertNothingLost() {
        assertEquals(listOf("home", "trips", "work"), calendars())
        assertEquals(3, runBlocking { store.everything() }.size)
        assertTrue(workEdit()!!.dirty)
        assertTrue(workEdit()!!.ics.contains("work event (moved)"))
    }

    @Test
    fun aCalendarListRefusedOrNotFoundFailsTheSyncAndRemovesNothing() {
        synced()
        for (status in listOf(403, 404, 405, 501)) {
            homeStatus = status
            val e = runCatching { runBlocking { engine.sync() } }.exceptionOrNull()
            assertEquals("$status", status, (e as? DavFailure)?.status)
            assertNothingLost()
        }
        assertTrue(puts.isEmpty())
    }

    @Test
    fun anEmptyCalendarListFailsTheSyncAndRemovesNothing() {
        synced()
        listed = emptyList()
        val e = runCatching { runBlocking { engine.sync() } }.exceptionOrNull()
        assertTrue("$e", e is DavFailure)
        assertNothingLost()
    }

    @Test
    fun aListNamingNoneOfTheCalendarsFailsTheSyncAndRemovesNothing() {
        // A home that answers with other collections only (a restore into the wrong folder).
        synced()
        listed = listOf("restored")
        val e = runCatching { runBlocking { engine.sync() } }.exceptionOrNull()
        assertTrue("$e", e is DavFailure)
        assertNothingLost()
    }

    @Test
    fun aCalendarGoneFromTheServerStaysWhileItHoldsAChangeNotYetSent() = runBlocking {
        synced()
        // Deleted on the laptop: "work" (with the edit) and "trips" (nothing to send).
        listed = listOf("home")
        engine.sync()
        assertEquals(listOf("home", "work"), calendars())
        assertTrue(workEdit()!!.dirty)
        assertTrue(store.everything().none { it.calendarId == cal("trips") })
        // Nothing is sent to a calendar that isn't there.
        assertTrue(puts.isEmpty())
        // The owner deletes the event: nothing is left to send, and the calendar goes.
        store.deleteLocal("work-row")
        engine.sync()
        assertEquals(listOf("home"), calendars())
        assertTrue(store.everything().none { it.calendarId == cal("work") })
    }

    @Test
    fun aCalendarThatComesBackSendsTheChangeKeptForIt() = runBlocking {
        synced()
        listed = listOf("home")
        engine.sync()
        listed = listOf("work", "home")
        engine.sync()
        assertEquals(listOf("$homePath" + "work/work-1.ics"), puts)
        assertTrue(!workEdit()!!.dirty)
    }

    @Test
    fun signingInAgainTakesTheCalendarsTheServerNowLists() = runBlocking {
        synced()
        runBlocking { store.saveLocal(store.resource("work-row")!!).let { store.pushed(it, it.href!!, "\"e3\"") } }
        listed = listOf("personal")
        engine.connect()
        assertEquals(listOf("personal"), calendars())
    }

    companion object {
        private const val MULTISTATUS = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/">%s</D:multistatus>"""
    }
}
