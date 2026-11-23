package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The year view's tint: each day in the colour of the calendar with the most events that day. */
class YearLayoutTest {
    private val utc = ZoneOffset.UTC

    private fun timed(cal: String, day: String, from: String) =
        occurrence(Instant.parse("${day}T$from:00Z"), Instant.parse("${day}T$from:00Z").plusSeconds(3600), LocalDate.parse(day), "E").copy(calendarId = cal)

    private fun allDay(cal: String, first: String, last: String) =
        occurrence(LocalDate.parse(first).atStartOfDay(utc).toInstant(), LocalDate.parse(last).plusDays(1).atStartOfDay(utc).toInstant(), LocalDate.parse(first), "Trip", allDay = true, lastDay = LocalDate.parse(last)).copy(calendarId = cal)

    @Test
    fun aDayTakesTheCalendarWithTheMostEventsThatDay() {
        val list = listOf(
            timed("work", "2026-11-24", "09:00"),
            timed("home", "2026-11-24", "12:00"),
            timed("home", "2026-11-24", "18:00"),
            timed("work", "2026-11-25", "09:00"),
        )
        val tints = YearLayout.tints(list, 2026)
        assertEquals("home", tints[LocalDate.parse("2026-11-24")])
        assertEquals("work", tints[LocalDate.parse("2026-11-25")])
        assertNull(tints[LocalDate.parse("2026-11-26")])
    }

    @Test
    fun aTieGoesToTheCalendarWhoseEventComesFirst() {
        val list = listOf(timed("family", "2026-11-24", "08:00"), timed("work", "2026-11-24", "09:00"))
        assertEquals("family", YearLayout.tints(list, 2026)[LocalDate.parse("2026-11-24")])
        assertEquals("work", YearLayout.tints(list.reversed(), 2026)[LocalDate.parse("2026-11-24")])
    }

    @Test
    fun anEventCountsOnEveryDayItCoversWithinTheYear() {
        val trip = allDay("family", "2026-12-30", "2027-01-02")
        val tints = YearLayout.tints(listOf(trip, timed("work", "2026-12-31", "09:00"), timed("work", "2026-12-31", "10:00")), 2026)
        assertEquals("family", tints[LocalDate.parse("2026-12-30")])
        assertEquals("work", tints[LocalDate.parse("2026-12-31")])
        assertEquals(2, tints.size)
        assertEquals(setOf(LocalDate.parse("2027-01-01"), LocalDate.parse("2027-01-02")), YearLayout.tints(listOf(trip), 2027).keys)
    }

    @Test
    fun aYearIsTwelveMonthsOfTheirOwnWeeks() {
        val months = YearLayout.months(2026, DayOfWeek.MONDAY)
        assertEquals(12, months.size)
        assertEquals(LocalDate.parse("2025-12-29"), months[0].first().first())
        assertEquals(6, months[10].size) // November 2026 starts on a Sunday
    }
}
