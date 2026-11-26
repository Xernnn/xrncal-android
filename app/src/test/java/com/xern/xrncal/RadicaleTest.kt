package com.xern.xrncal

import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.DavFailure
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryAccount
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.ResourceEntity
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.SyncEngine
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Scope
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The phone's sync engine against a real Radicale 3.8.1, started here on a free port with a
 * throwaway folder and a made-up user. "The laptop" is another client writing to the same server.
 * Skipped where Radicale isn't installed: XRNCAL_RADICALE names its executable, else `radicale`
 * on the PATH (`python3 -m venv <dir> && <dir>/bin/pip install radicale==3.8.1`).
 */
class RadicaleTest {
    private lateinit var dir: File
    private var server: Process? = null
    private var port = 0
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private var now = Instant.parse("2026-11-24T02:00:00Z")

    private val user = "alex"
    private val password = "made-up-password-1"

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    private val root get() = "http://127.0.0.1:$port"
    private val work get() = "$root/$user/work/"
    private val home get() = "$root/$user/home/"

    @Before
    fun start() {
        val bin = System.getenv("XRNCAL_RADICALE")?.takeIf { it.isNotBlank() } ?: "radicale"
        assumeTrue("no radicale (set XRNCAL_RADICALE)", run(bin, "--version") == 0)
        dir = Files.createTempDirectory("xrncal-radicale").toFile()
        port = ServerSocket(0).use { it.localPort }
        File(dir, "users").writeText("$user:$password\n")
        File(dir, "config").writeText(
            """
            [server]
            hosts = 127.0.0.1:$port
            [auth]
            type = htpasswd
            htpasswd_filename = ${File(dir, "users").absolutePath}
            htpasswd_encryption = plain
            [rights]
            type = owner_only
            [storage]
            filesystem_folder = ${File(dir, "collections").absolutePath}
            [logging]
            level = warning
            mask_passwords = True
            """.trimIndent() + "\n",
        )
        server = ProcessBuilder(bin, "--config", File(dir, "config").absolutePath).redirectErrorStream(true).redirectOutput(File(dir, "server.log")).start()
        repeat(100) {
            if (runCatching { http.newCall(Request.Builder().url("$root/").build()).execute().use { it.code } }.isSuccess) {
                mkcalendar(work, "Work", "#33B679FF")
                mkcalendar(home, "Home", "#4285F4")
                return
            }
            Thread.sleep(100)
        }
        error("Radicale didn't start: " + File(dir, "server.log").readText())
    }

    @After
    fun stop() {
        server?.destroy()
        server?.waitFor(10, TimeUnit.SECONDS)
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun run(vararg cmd: String): Int = try {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor()
        }
    } catch (e: Exception) {
        -1
    }

    // ---- the laptop: another client of the same server ----

    private fun request(method: String, url: String, body: String? = null, type: String = "text/calendar; charset=utf-8", headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val req = Request.Builder().url(url).method(method, body?.toRequestBody(type.toMediaType()))
            .header("Authorization", Credentials.basic(user, password)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun mkcalendar(url: String, name: String, color: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:I="http://apple.com/ns/ical/">
  <D:set><D:prop><D:displayname>$name</D:displayname><I:calendar-color>$color</I:calendar-color>
  <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set></D:prop></D:set>
</C:mkcalendar>"""
        val (status, text) = request("MKCALENDAR", url, body, "application/xml; charset=utf-8")
        assertEquals(text, 201, status)
    }

    private fun laptopPut(url: String, ics: String) = assertTrue(request("PUT", url, ics).first in 200..299)
    private fun laptopGet(url: String): String? = request("GET", url).let { if (it.first == 404) null else it.second }
    private fun laptopDelete(url: String) = request("DELETE", url).first
    private fun assertOk(status: Int) = assertTrue("$status", status in 200..299)

    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Laptop//EN\r\n" + events.joinToString("") + "END:VCALENDAR\r\n"
    private fun ev(vararg lines: String) = "BEGIN:VEVENT\r\nDTSTAMP:20261101T000000Z\r\n" + lines.joinToString("\r\n") + "\r\nEND:VEVENT\r\n"

    private val dentist = ics(ev("UID:dentist-1", "SUMMARY:Dentist", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261126T090000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261126T100000", "LOCATION:12 Made-up Street"))
    private val standup = ics(
        ev("UID:standup-1", "SUMMARY:Standup", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261123T093000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261123T094500", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE", "X-LAPTOP-NOTE:kept by the phone"),
        ev("UID:standup-1", "RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20261125T093000", "SUMMARY:Standup (moved)", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261125T140000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261125T141500"),
    )
    private val trip = ics(ev("UID:trip-1", "SUMMARY:Trip to Huế", "DTSTART;VALUE=DATE:20261128", "DTEND;VALUE=DATE:20261201"))

    // ---- the phone ----

    inner class Phone(address: String = "$root/") {
        val store = MemoryCalendarStore()
        val account = MemoryAccount().apply {
            this.address = address
            user = this@RadicaleTest.user
            password = this@RadicaleTest.password
        }
        val engine = SyncEngine(store, account, Dispatchers.IO)
        val events = EventRepository(store, { Settings() }, { zone }, { now })

        fun sync() = runBlocking { engine.sync() }
        fun week(from: String = "2026-11-23", to: String = "2026-11-30") = runBlocking { events.occurrences(LocalDate.parse(from), LocalDate.parse(to)) }
        fun titles(from: String = "2026-11-23", to: String = "2026-11-30") = week(from, to).map { it.title }
        fun one(title: String): Occurrence = week().first { it.title == title }
        fun rows() = runBlocking { store.everything() }
    }

    private fun draft(title: String, day: String, from: String, to: String) =
        EventDraft(title, false, LocalDate.parse(day), LocalTime.parse(from), LocalDate.parse(day), LocalTime.parse(to), zone)

    @Test
    fun findsTheCalendarsFromNothingButTheServerAddress() = runBlocking {
        val phone = Phone()
        val found = phone.engine.connect()
        assertEquals("$root/$user/", phone.account.home)
        assertEquals(listOf("Home", "Work"), found.map { it.name }.sorted())
        val cals = phone.store.calendars().associateBy { it.name }
        assertEquals(0xFF33B679.toInt(), cals.getValue("Work").color)
        assertEquals(0xFF4285F4.toInt(), cals.getValue("Home").color)
        assertTrue(cals.values.none { it.readOnly })
    }

    @Test
    fun aWrongPasswordSaysSo() = runBlocking {
        val phone = Phone()
        phone.account.password = "not-it"
        val e = runCatching { phone.engine.connect() }.exceptionOrNull()
        assertEquals(401, (e as DavFailure).status)
    }

    @Test
    fun theFirstSyncBringsEveryEventAndItsDates() {
        laptopPut(work + "dentist-1.ics", dentist)
        laptopPut(work + "standup-1.ics", standup)
        laptopPut(home + "trip-1.ics", trip)
        val phone = Phone()
        val report = phone.sync()
        assertEquals(3, report.pulled)
        val week = phone.week()
        assertEquals(setOf("Standup", "Dentist", "Standup (moved)", "Trip to Huế"), week.map { it.title }.toSet())
        assertEquals(2, week.count { it.title == "Standup" }) // Mon 23 and Mon 30; Wed 25 is the moved one
        val moved = phone.one("Standup (moved)")
        assertEquals(Instant.parse("2026-11-25T07:00:00Z"), moved.start)
        assertTrue(moved.exception)
        val tripDays = phone.one("Trip to Huế")
        assertEquals(LocalDate.parse("2026-11-28") to LocalDate.parse("2026-11-30"), tripDays.firstDay to tripDays.lastDay)
        // Each calendar remembers where it got to.
        runBlocking { phone.store.calendars() }.filter { it.account == CalendarEntity.CALDAV }.forEach {
            assertNotNull(it.ctag)
            assertNotNull(it.syncToken)
            assertNotEquals(CalendarEntity.NO_SYNC_COLLECTION, it.syncToken)
        }
    }

    @Test
    fun anIncrementalSyncTakesOnlyWhatChanged() {
        laptopPut(work + "dentist-1.ics", dentist)
        laptopPut(work + "standup-1.ics", standup)
        laptopPut(home + "trip-1.ics", trip)
        val phone = Phone()
        phone.sync()
        // Nothing changed: the ctags say so, and nothing is read.
        assertEquals(0, phone.sync().pulled)
        laptopPut(work + "dentist-1.ics", dentist.replace("SUMMARY:Dentist", "SUMMARY:Dentist (check-up)"))
        assertOk(laptopDelete(home + "trip-1.ics"))
        val report = phone.sync()
        assertEquals(1, report.pulled)
        assertEquals(1, report.removed)
        assertTrue("Dentist (check-up)" in phone.titles())
        assertTrue("Trip to Huế" !in phone.titles())
    }

    @Test
    fun anEditMadeOfflineIsSentLater() {
        laptopPut(work + "dentist-1.ics", dentist)
        val phone = Phone()
        phone.sync()
        // The server out of reach: nothing is lost, the edit waits.
        phone.account.address = "http://127.0.0.1:1/"
        phone.account.home = null
        val dentistOcc = phone.one("Dentist")
        val moved = runBlocking { phone.events.draft(dentistOcc, Scope.ALL)!! }.copy(title = "Dentist, moved", startDate = LocalDate.parse("2026-11-27"), endDate = LocalDate.parse("2026-11-27"), startTime = LocalTime.parse("15:00"), endTime = LocalTime.parse("16:00"))
        runBlocking { phone.events.update(dentistOcc, moved, Scope.ALL) }
        val made = runBlocking { phone.events.create(work, draft("Lunch with Sam", "2026-11-26", "12:00", "13:00")) }
        val offline = runCatching { phone.sync() }.exceptionOrNull()
        assertEquals(0, (offline as DavFailure).status)
        assertTrue(phone.rows().filter { it.calendarId == work }.all { it.dirty })
        phone.account.address = "$root/"
        val report = phone.sync()
        assertEquals(2, report.pushed)
        assertTrue(phone.rows().none { it.dirty })
        val server = laptopGet(work + "dentist-1.ics")!!
        assertTrue(server.contains("SUMMARY:Dentist\\, moved"))
        assertTrue(server.contains("DTSTART;TZID=Asia/Ho_Chi_Minh:20261127T150000"))
        assertTrue(server.contains("LOCATION:12 Made-up Street"))
        val lunch = runBlocking { phone.store.resource(made) }!!
        assertTrue(laptopGet(lunch.href!!)!!.contains("SUMMARY:Lunch with Sam"))
        assertTrue(lunch.href!!.startsWith(work))
    }

    @Test
    fun anEditMadeOnBothSidesIsAConflictNotAnOverwrite() {
        laptopPut(work + "dentist-1.ics", dentist)
        val phone = Phone()
        phone.sync()
        laptopPut(work + "dentist-1.ics", dentist.replace("SUMMARY:Dentist", "SUMMARY:Dentist (laptop)"))
        runBlocking { phone.events.update(phone.one("Dentist"), draft("Dentist (phone)", "2026-11-26", "09:00", "10:00"), Scope.ALL) }
        val report = phone.sync()
        assertEquals(1, report.problems)
        val row = phone.rows().single { it.uid == "dentist-1" }
        assertEquals(ResourceEntity.CONFLICT, row.problem)
        assertTrue(row.serverIcs!!.contains("Dentist (laptop)"))
        assertTrue(laptopGet(work + "dentist-1.ics")!!.contains("SUMMARY:Dentist (laptop)"))
        // Never sent again on its own.
        assertEquals(0, phone.sync().pushed)
        assertTrue(laptopGet(work + "dentist-1.ics")!!.contains("SUMMARY:Dentist (laptop)"))
        assertEquals(1, runBlocking { phone.events.problems() }.size)
        // The owner keeps the phone's.
        runBlocking { phone.events.keepMine(row.id) }
        assertEquals(1, phone.sync().pushed)
        assertTrue(laptopGet(work + "dentist-1.ics")!!.contains("SUMMARY:Dentist (phone)"))
        assertTrue(phone.rows().none { it.problem != null || it.dirty })
    }

    @Test
    fun keepTheirsTakesTheServersVersion() {
        laptopPut(work + "dentist-1.ics", dentist)
        val phone = Phone()
        phone.sync()
        laptopPut(work + "dentist-1.ics", dentist.replace("SUMMARY:Dentist", "SUMMARY:Dentist (laptop)"))
        runBlocking { phone.events.update(phone.one("Dentist"), draft("Dentist (phone)", "2026-11-26", "09:00", "10:00"), Scope.ALL) }
        phone.sync()
        val row = phone.rows().single { it.uid == "dentist-1" }
        runBlocking { phone.engine.keepTheirs(row.id) }
        assertTrue("Dentist (laptop)" in phone.titles())
        assertTrue(phone.rows().none { it.problem != null || it.dirty })
        assertEquals(0, phone.sync().pushed)
    }

    @Test
    fun oneDateOfASeriesIsChangedAndAnotherRemovedKeepingTheRest() {
        laptopPut(work + "standup-1.ics", standup)
        val phone = Phone()
        phone.sync()
        val monday = phone.week().first { it.title == "Standup" && it.firstDay == LocalDate.parse("2026-11-23") }
        val nextMonday = phone.week("2026-11-30", "2026-11-30").first { it.title == "Standup" }
        runBlocking {
            phone.events.update(monday, draft("Standup (long)", "2026-11-23", "09:30", "10:30"), Scope.THIS)
            phone.events.delete(nextMonday, Scope.THIS)
        }
        assertEquals(1, phone.sync().pushed)
        val server = laptopGet(work + "standup-1.ics")!!
        assertTrue(server, server.contains("RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20261123T093000"))
        assertTrue(server.contains("SUMMARY:Standup (long)"))
        assertTrue(server.contains("EXDATE;TZID=Asia/Ho_Chi_Minh:20261130T093000"))
        assertTrue(server.contains("RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20261125T093000"))
        assertTrue("what another program wrote stays", server.contains("X-LAPTOP-NOTE:kept by the phone"))
        // A second phone reads it back the same.
        val other = Phone()
        other.sync()
        assertEquals(listOf("Standup (long)", "Standup (moved)"), other.week("2026-11-23", "2026-11-29").map { it.title })
        assertTrue(other.week("2026-11-30", "2026-11-30").none { it.title.startsWith("Standup") })
        assertEquals(1, other.week("2026-12-02", "2026-12-02").count { it.title == "Standup" })
    }

    @Test
    fun deletesGoBothWays() {
        laptopPut(work + "dentist-1.ics", dentist)
        laptopPut(home + "trip-1.ics", trip)
        val phone = Phone()
        phone.sync()
        runBlocking { phone.events.delete(phone.one("Dentist"), Scope.ALL) }
        assertTrue("Dentist" !in phone.titles())
        assertEquals(1, phone.sync().pushed)
        assertNull(laptopGet(work + "dentist-1.ics"))
        assertTrue(phone.rows().none { it.uid == "dentist-1" })
        assertOk(laptopDelete(home + "trip-1.ics"))
        phone.sync()
        assertTrue(phone.rows().none { it.uid == "trip-1" })
    }

    @Test
    fun aCalendarHomeMissingOrEmptiedFailsTheSyncAndLosesNoUnsentEdit() {
        laptopPut(work + "dentist-1.ics", dentist)
        laptopPut(home + "trip-1.ics", trip)
        val phone = Phone()
        phone.sync()
        runBlocking { phone.events.update(phone.one("Dentist"), draft("Dentist (phone)", "2026-11-26", "09:00", "10:00"), Scope.ALL) }
        fun assertKept() {
            assertEquals(listOf("Home", "Work"), runBlocking { phone.store.calendars() }.filter { !it.local }.map { it.name }.sorted())
            assertTrue(phone.rows().single { it.uid == "dentist-1" }.dirty)
            assertTrue(phone.rows().any { it.uid == "trip-1" })
        }
        // The home answers 404 (a restore into another folder).
        val found = phone.account.home
        phone.account.home = "$root/$user/elsewhere/"
        assertEquals(404, (runCatching { phone.sync() }.exceptionOrNull() as DavFailure).status)
        assertKept()
        // The home lists no calendar at all.
        phone.account.home = found
        assertOk(laptopDelete(work))
        assertOk(laptopDelete(home))
        assertTrue(runCatching { phone.sync() }.exceptionOrNull() is DavFailure)
        assertKept()
    }

    @Test
    fun anEventDeletedOnTheServerWhileChangedHereIsKeptAsAConflict() {
        laptopPut(work + "dentist-1.ics", dentist)
        val phone = Phone()
        phone.sync()
        assertOk(laptopDelete(work + "dentist-1.ics"))
        phone.account.address = "http://127.0.0.1:1/"
        runBlocking { phone.events.update(phone.one("Dentist"), draft("Dentist (phone)", "2026-11-26", "09:00", "10:00"), Scope.ALL) }
        phone.account.address = "$root/"
        phone.sync()
        val row = phone.rows().single { it.uid == "dentist-1" }
        assertTrue(row.problem == ResourceEntity.GONE || row.problem == ResourceEntity.CONFLICT)
        assertTrue("Dentist (phone)" in phone.titles())
        runBlocking { phone.events.keepMine(row.id) }
        phone.sync()
        assertTrue(phone.rows().none { it.problem != null || it.dirty })
        val server = request("REPORT", work, """<?xml version="1.0"?><C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><C:calendar-data/></D:prop><C:filter><C:comp-filter name="VCALENDAR"/></C:filter></C:calendar-query>""", "application/xml", mapOf("Depth" to "1")).second
        assertTrue(server.contains("Dentist (phone)"))
    }

    @Test
    fun anEventMovesToAnotherCalendarUnderANameRadicaleTakes() {
        // Made by another program: a UID with colons and a slash, which Radicale refuses in a path.
        // (Not a comma: Radicale keeps a UID only up to its first comma, whoever sends it.)
        val odd = ics(ev("UID:urn:uuid:9f3c-77/a", "SUMMARY:Planning", "DTSTART:20261126T020000Z", "DTEND:20261126T030000Z"))
        laptopPut(work + "planning.ics", odd)
        val phone = Phone()
        phone.sync()
        val planning = phone.one("Planning")
        runBlocking { phone.events.update(planning, phone.events.draft(planning, Scope.ALL)!!, Scope.ALL, calendarId = home) }
        assertEquals(2, phone.sync().pushed)
        assertNull(laptopGet(work + "planning.ics"))
        val moved = phone.rows().single { it.uid == "urn:uuid:9f3c-77/a" }
        assertEquals(home, moved.calendarId)
        assertTrue(moved.href!!.matches(Regex(Regex.escape(home) + "[0-9a-f]{40}\\.ics")))
        assertTrue(laptopGet(moved.href!!)!!.contains("UID:urn:uuid:9f3c-77/a"))
    }

    @Test
    fun aSecondPhoneSeesWhatTheFirstMade() {
        val phone = Phone()
        phone.sync()
        runBlocking { phone.events.create(home, draft("Phở with Tuấn", "2026-11-28", "18:30", "20:00").copy(rrule = "FREQ=WEEKLY", reminders = emptyList(), notes = "Bring the made-up voucher")) }
        phone.sync()
        val other = Phone()
        other.sync()
        val o = other.week().single { it.title == "Phở with Tuấn" }
        assertEquals(Instant.parse("2026-11-28T11:30:00Z"), o.start)
        assertTrue(o.recurring)
        // Set to none: no VALARM at all, which never rings on either app.
        assertNull(o.reminders)
        assertEquals("Bring the made-up voucher", o.notes)
    }

    private fun propfindColor(url: String): String =
        request("PROPFIND", url, """<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:I="http://apple.com/ns/ical/"><D:prop><I:calendar-color/></D:prop></D:propfind>""", "application/xml", mapOf("Depth" to "0")).second

    @Test
    fun aCalendarColourPickedOnThePhoneIsWrittenToTheServerAndReadByASecondDevice() {
        val phone = Phone()
        phone.sync()
        val blueberry = 0xFF3F51B5.toInt()
        runBlocking { phone.events.setColor(work, blueberry, com.xern.xrncal.data.MemorySettings()) }
        assertEquals(CalendarEntity.COLOR_TO_SEND, runBlocking { phone.store.calendar(work) }!!.colorState)
        phone.sync()
        val after = runBlocking { phone.store.calendar(work) }!!
        assertEquals(blueberry, after.color)
        assertEquals(CalendarEntity.COLOR_SERVER, after.colorState)
        assertTrue(propfindColor(work), propfindColor(work).contains("#3F51B5FF"))
        // Another device (the laptop, a second phone) reads it back.
        val other = Phone()
        other.sync()
        assertEquals(blueberry, runBlocking { other.store.calendar(work) }!!.color)
        // And a colour changed elsewhere later is the server's again here.
        val body = """<?xml version="1.0"?><D:propertyupdate xmlns:D="DAV:" xmlns:I="http://apple.com/ns/ical/"><D:set><D:prop><I:calendar-color>#795548FF</I:calendar-color></D:prop></D:set></D:propertyupdate>"""
        assertEquals(207, request("PROPPATCH", work, body, "application/xml").first)
        phone.sync()
        assertEquals(0xFF795548.toInt(), runBlocking { phone.store.calendar(work) }!!.color)
    }

    @Test
    fun anEventsOwnColourGoesToTheServerAndComesBack() {
        val phone = Phone()
        phone.sync()
        val id = runBlocking { phone.events.create(home, draft("Dentist", "2026-11-26", "09:00", "10:00").copy(color = 0xFFD50000.toInt())) }
        phone.sync()
        val href = runBlocking { phone.store.resource(id) }!!.href!!
        assertTrue(laptopGet(href)!!.contains("COLOR:#d50000"))
        val other = Phone()
        other.sync()
        assertEquals(0xFFD50000.toInt(), other.one("Dentist").color)
        // The laptop picks another; the phone shows it.
        laptopPut(work + "call-1.ics", ics(ev("UID:call-1", "SUMMARY:Call", "COLOR:#039be5", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261127T090000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261127T093000")))
        phone.sync()
        assertEquals(0xFF039BE5.toInt(), phone.one("Call").color)
    }

    @Test
    fun undoAfterTheDeleteReachedTheServerMakesTheEventAgain() {
        laptopPut(work + "dentist-1.ics", dentist)
        val phone = Phone()
        phone.sync()
        val u = runBlocking { phone.events.delete(phone.one("Dentist"), Scope.ALL) }!!
        phone.sync()
        assertNull(laptopGet(work + "dentist-1.ics"))
        runBlocking { phone.events.undo(u) }
        phone.sync()
        val other = Phone()
        other.sync()
        val back = other.one("Dentist")
        assertEquals("dentist-1", back.uid)
        assertEquals("12 Made-up Street", back.location)
    }

    @Test
    fun aNewEventsDefaultReminderReachesTheServerAsItsOwnAlarm() {
        val phone = Phone()
        phone.sync()
        val defaults = com.xern.xrncal.domain.Reminders.ONE_CALENDAR_DEFAULTS
        val id = runBlocking { phone.events.create(home, draft("Dentist", "2026-11-26", "09:00", "10:00").copy(reminders = defaults.forNew(false))) }
        phone.sync()
        val server = laptopGet(runBlocking { phone.store.resource(id) }!!.href!!)!!
        assertTrue(server, server.contains("BEGIN:VALARM") && server.contains("TRIGGER:-PT15M") && !server.contains("ACTION:NONE"))
        val other = Phone()
        other.sync()
        assertEquals(listOf(com.xern.xrncal.domain.EventReminder(15)), other.one("Dentist").reminders)
    }
}
