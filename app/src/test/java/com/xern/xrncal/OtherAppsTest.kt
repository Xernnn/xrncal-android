package com.xern.xrncal

import android.content.Intent
import android.provider.CalendarContract
import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.ResourceEntity
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.Edits
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.IcsFiles
import com.xern.xrncal.domain.InsertRequest
import com.xern.xrncal.domain.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What other apps hand the calendar, read under the names Android gives it (docs/spec.md, "Other apps"). */
class OtherAppsTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-11-24T01:00:00Z")

    /** The domain reads the insert intent's extras by name; these are Android's own. */
    @Test
    fun theInsertRequestReadsAndroidsExtraNames() {
        assertEquals(CalendarContract.Events.TITLE, InsertRequest.TITLE)
        assertEquals(CalendarContract.Events.DESCRIPTION, InsertRequest.DESCRIPTION)
        assertEquals(CalendarContract.Events.EVENT_LOCATION, InsertRequest.LOCATION)
        assertEquals(CalendarContract.Events.RRULE, InsertRequest.RRULE)
        assertEquals(CalendarContract.Events.EVENT_TIMEZONE, InsertRequest.TIME_ZONE)
        assertEquals(CalendarContract.EXTRA_EVENT_BEGIN_TIME, InsertRequest.BEGIN)
        assertEquals(CalendarContract.EXTRA_EVENT_END_TIME, InsertRequest.END)
        assertEquals(CalendarContract.EXTRA_EVENT_ALL_DAY, InsertRequest.ALL_DAY)
        assertEquals(Intent.EXTRA_EMAIL, InsertRequest.EMAIL)
    }

    /** A file of all-day events, each `uid|title|yyyyMMdd`. */
    private fun ics(vararg events: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Made-up Exporter//EN") + events.flatMap { e ->
            val (uid, title, day) = e.split('|')
            listOf("BEGIN:VEVENT", "UID:$uid", "SUMMARY:$title", "DTSTART;VALUE=DATE:$day", "END:VEVENT")
        } + "END:VCALENDAR").joinToString("\r\n")

    private fun event(uid: String, title: String) =
        Edits.create(EventDraft(title, true, LocalDate.parse("2026-12-01"), LocalTime.MIDNIGHT, LocalDate.parse("2026-12-01"), LocalTime.MIDNIGHT, zone), uid, now)

    /**
     * Several events imported into a calendar: each a new resource to send. One the calendar holds
     * is counted and skipped; one another calendar holds is copied under a UID of its own; a
     * second import of the same file adds nothing.
     */
    @Test
    fun anImportAddsWhatTheCalendarLacksAndCountsTheRest() = runBlocking {
        val store = MemoryCalendarStore()
        var changes = 0
        val events = EventRepository(store, { Settings() }, { zone }, { now }, changed = { changes++ })
        store.putCalendar(CalendarEntity("home", CalendarEntity.LOCAL, "Phone", 0))
        store.putCalendar(CalendarEntity("work", CalendarEntity.CALDAV, "Work", 0))
        store.putRaw(ResourceEntity.indexed(ResourceEntity("r1", "home", ics = event("fair@example.com", "Book fair"))))
        store.putRaw(ResourceEntity.indexed(ResourceEntity("r2", "work", ics = event("review@example.com", "Year review"))))
        val file = IcsFiles.read(
            ics("fair@example.com|Book fair|20261219", "review@example.com|Year review|20261203", "picnic@example.com|Picnic|20261213"),
            zone, Reminders.Defaults(15, 720),
        ) as IcsFiles.Read.Many

        assertEquals(EventRepository.ImportResult(added = 2, alreadyThere = 1), events.import("home", file.events))
        assertEquals(1, changes)
        val added = store.inCalendar("home").filter { it.id != "r1" }
        assertEquals(setOf("Year review", "Picnic"), added.map { it.title }.toSet())
        assertTrue(added.all { it.dirty && it.href == null })
        assertEquals("picnic@example.com", added.single { it.title == "Picnic" }.uid)
        assertEquals(IcsFiles.copyUid("review@example.com", "home"), added.single { it.title == "Year review" }.uid)
        assertEquals("review@example.com", store.inCalendar("work").single().uid)

        assertEquals(EventRepository.ImportResult(added = 0, alreadyThere = 3), events.import("home", file.events))
        assertEquals(3, store.inCalendar("home").size)
        assertEquals(1, changes)
    }
}
