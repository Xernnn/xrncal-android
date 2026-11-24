package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Other apps' "add to calendar": the insert intent's extras to a new event's draft (docs/spec.md, "Other apps"). */
class InsertRequestTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
    // 12:20 in Sydney on Tuesday 24 November 2026.
    private val now = Instant.parse("2026-11-24T01:20:00Z")
    private val defaults = Reminders.Defaults(timed = 15, allDay = 720)

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    private fun draft(vararg extras: Pair<String, Any?>, minutes: Int = 60) = InsertRequest.draft(mapOf(*extras), now, sydney, minutes, defaults)

    @Test
    fun theExtrasFillANewEventInTheZoneTheyName() {
        val d = draft(
            InsertRequest.TITLE to "Dinner with Sam",
            InsertRequest.DESCRIPTION to "Table for two",
            InsertRequest.LOCATION to " 12 Made-up Street ",
            InsertRequest.BEGIN to ms("2026-12-04T09:30:00Z"),
            InsertRequest.END to ms("2026-12-04T11:00:00Z"),
            InsertRequest.TIME_ZONE to "Asia/Ho_Chi_Minh",
            InsertRequest.RRULE to "FREQ=WEEKLY;BYDAY=FR",
            InsertRequest.EMAIL to "sam@example.com, alex@example.org",
        )
        assertEquals("Dinner with Sam", d.title)
        assertEquals("12 Made-up Street", d.location)
        assertEquals(false, d.allDay)
        assertEquals(hcm, d.zone)
        assertEquals(LocalDate.of(2026, 12, 4), d.startDate)
        assertEquals(LocalTime.of(16, 30), d.startTime)
        assertEquals(LocalTime.of(18, 0), d.endTime)
        assertEquals("FREQ=WEEKLY;BYDAY=FR", d.rrule)
        assertEquals("Table for two\n\nGuests: sam@example.com, alex@example.org", d.notes)
        assertEquals(listOf(EventReminder(15)), d.reminders)
    }

    @Test
    fun missingTimesAreANewEventsTheNextHourForTheDefaultLength() {
        val d = draft(InsertRequest.TITLE to "Call the plumber", minutes = 45)
        assertEquals(sydney, d.zone)
        assertEquals(LocalDate.of(2026, 11, 24), d.startDate)
        assertEquals(LocalTime.of(13, 0), d.startTime)
        assertEquals(LocalTime.of(13, 45), d.endTime)
        assertEquals("", d.notes)
        assertNull(d.rrule)
    }

    @Test
    fun aStartAloneOrAnEndBeforeItTakesTheDefaultLength() {
        val begin = ms("2026-11-30T22:00:00Z") // 09:00 on 1 December in Sydney
        for (end in listOf(null, ms("2026-11-30T21:00:00Z"))) {
            val d = draft(InsertRequest.BEGIN to begin, InsertRequest.END to end, minutes = 30)
            assertEquals(LocalDate.of(2026, 12, 1), d.startDate)
            assertEquals(LocalTime.of(9, 0), d.startTime)
            assertEquals(LocalTime.of(9, 30), d.endTime)
        }
    }

    @Test
    fun anAllDayEventCoversItsDaysWhicheverMidnightTheAppSent() {
        // Android's own convention: midnight UTC, the end the midnight after the last day.
        val utc = draft(InsertRequest.ALL_DAY to true, InsertRequest.BEGIN to ms("2026-12-24T00:00:00Z"), InsertRequest.END to ms("2026-12-26T00:00:00Z"))
        assertEquals(true, utc.allDay)
        assertEquals(LocalDate.of(2026, 12, 24), utc.startDate)
        assertEquals(LocalDate.of(2026, 12, 25), utc.endDate)
        assertEquals(listOf(EventReminder(720)), utc.reminders)
        // The phone's own midnight (13:00 UTC the day before, in Sydney's summer), no end.
        val local = draft(InsertRequest.ALL_DAY to "true", InsertRequest.BEGIN to ms("2026-12-23T13:00:00Z").toString())
        assertEquals(LocalDate.of(2026, 12, 24), local.startDate)
        assertEquals(LocalDate.of(2026, 12, 24), local.endDate)
        // No times at all: today.
        val today = draft(InsertRequest.ALL_DAY to true)
        assertEquals(LocalDate.of(2026, 11, 24), today.startDate)
        assertEquals(today.startDate, today.endDate)
    }

    @Test
    fun guestsComeAsAnArrayOrAList() {
        assertEquals(listOf("sam@example.com", "kim@example.org"), InsertRequest.emails(arrayOf("sam@example.com", " mailto:kim@example.org", "sam@example.com")))
        assertEquals(listOf("sam@example.com"), InsertRequest.emails(listOf("sam@example.com", "")))
        assertEquals(emptyList<String>(), InsertRequest.emails(null))
        assertEquals("Guests: sam@example.com", draft(InsertRequest.EMAIL to arrayOf("sam@example.com")).notes)
    }

    @Test
    fun aRuleOrAZoneTheAppCannotReadIsLeftOut() {
        val d = draft(InsertRequest.RRULE to "every other week", InsertRequest.TIME_ZONE to "Somewhere/Nowhere", InsertRequest.BEGIN to ms("2026-12-01T00:00:00Z"))
        assertNull(d.rrule)
        assertEquals(sydney, d.zone)
        assertEquals("FREQ=DAILY;COUNT=3", draft(InsertRequest.RRULE to "RRULE:FREQ=DAILY;COUNT=3").rrule)
    }

    @Test
    fun aNewEventStartsAtTheNextHour() {
        val d = NewEvents.draft(LocalDate.of(2026, 11, 24), null, now, sydney, 60)
        assertEquals(LocalTime.of(13, 0), d.startTime)
        assertEquals(LocalTime.of(14, 0), d.endTime)
        assertEquals(LocalTime.of(12, 0), NewEvents.draft(LocalDate.of(2026, 11, 25), null, now, sydney, 60).startTime)
        assertEquals(LocalTime.of(9, 0), NewEvents.draft(LocalDate.of(2026, 11, 25), 9, now, sydney, 60).startTime)
    }
}
