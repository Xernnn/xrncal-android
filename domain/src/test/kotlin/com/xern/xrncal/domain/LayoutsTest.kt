package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun d(s: String) = LocalDate.parse(s)

/** The month grid, the list and the time grid: where each event goes on screen. */
class LayoutsTest {
    private val utc = ZoneOffset.UTC

    private fun allDay(title: String, first: String, last: String = first, id: String = title) =
        occurrence(d(first).atStartOfDay(utc).toInstant(), d(last).plusDays(1).atStartOfDay(utc).toInstant(), d(first), title, allDay = true, lastDay = d(last), id = id)

    private fun timed(title: String, day: String, from: String, to: String, id: String = title) =
        occurrence(Instant.parse("${day}T${from}:00Z"), Instant.parse("${day}T${to}:00Z"), d(day), title, id = id)

    @Test
    fun aMonthStartsInTheWeekOfItsFirstDayWhicheverDayStartsTheWeek() {
        val mon = MonthLayout.weeks(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, mon.size)
        assertEquals(d("2026-10-26"), mon.first().first())
        assertEquals(d("2026-12-06"), mon.last().last())
        val sun = MonthLayout.weeks(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(d("2026-11-01"), sun.first().first())
        // Only the rows the month needs, when asked.
        assertEquals(5, MonthLayout.weeks(YearMonth.of(2026, 11), DayOfWeek.SUNDAY, rows = null).size)
        assertEquals(4, MonthLayout.weeks(YearMonth.of(2026, 2), DayOfWeek.SUNDAY, rows = null).size)
    }

    private val week = (23..29).map { d("2026-11-$it") }

    @Test
    fun aSpanIsOneBarAcrossItsDaysOnTheTopLine() {
        val trip = allDay("Trip", "2026-11-25", "2026-11-28")
        val lunch = timed("Lunch", "2026-11-25", "05:00", "06:00")
        val w = MonthLayout.week(week, listOf(lunch, trip), lanes = 3)
        val bar = w.bars.single { it.occurrence.title == "Trip" }
        assertEquals(Triple(2, 5, 0), Triple(bar.from, bar.to, bar.lane))
        assertEquals(1, w.bars.single { it.occurrence.title == "Lunch" }.lane)
    }

    @Test
    fun aSpanOverTheWeekEndContinuesOnTheNextRow() {
        val trip = allDay("Trip", "2026-11-28", "2026-12-02")
        val first = MonthLayout.week(week, listOf(trip), 3).bars.single()
        assertEquals(5 to 6, first.from to first.to)
        assertTrue(first.continuesAfter)
        val next = MonthLayout.week((30..36).map { d("2026-11-01").plusDays(it - 1L) }, listOf(trip), 3).bars.single()
        assertEquals(0 to 2, next.from to next.to)
        assertTrue(next.continuesBefore)
    }

    @Test
    fun aMonthCellFillsWithOneLineChipsAsOneCalendarDoes() {
        // The owner's phone: a 126 dp row under a 22 dp day number, 18 dp chips 2 dp apart.
        assertEquals(5, MonthLayout.lanes(126f, 22f, 18f, 2f))
        assertEquals(4, MonthLayout.lanes(100f, 22f, 18f, 2f))
        assertEquals(1, MonthLayout.lanes(20f, 22f, 18f, 2f))
        // Seven events on a day of five lines: five chips, and "+2" beside the number.
        val seven = (1..7).map { timed("E$it", "2026-11-24", "0$it:00", "0$it:30") }
        val w = MonthLayout.week(week, seven, lanes = 5)
        assertEquals(5, w.bars.size)
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0), w.more)
        assertEquals((1..5).map { "E$it" }, w.bars.sortedBy { it.lane }.map { it.occurrence.title })
    }

    @Test
    fun aFullDayCanKeepItsLastLineForTheCountOfTheRest() {
        val many = (1..5).map { timed("E$it", "2026-11-24", "0$it:00", "0$it:30") }
        val w = MonthLayout.week(week, many, lanes = 3, keepLineForMore = true)
        assertEquals(2, w.bars.size)
        assertEquals(listOf(0, 3, 0, 0, 0, 0, 0), w.more)
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0), MonthLayout.week(week, many, lanes = 3).more)
        // A day with as many as fit shows them all.
        val three = MonthLayout.week(week, many.take(3), lanes = 3)
        assertEquals(3, three.bars.size)
        assertEquals(List(7) { 0 }, three.more)
    }

    @Test
    fun aSpanKeepsItsTopLineOnABusyDay() {
        val trip = allDay("Trip", "2026-11-23", "2026-11-25")
        val busy = (1..4).map { timed("E$it", "2026-11-24", "0$it:00", "0$it:30") }
        val w = MonthLayout.week(week, busy + trip, lanes = 3)
        val trips = w.bars.filter { it.occurrence.title == "Trip" }.map { it.from to it.to }
        assertEquals(listOf(0 to 2), trips)
        assertEquals(2, w.more[1])
    }

    @Test
    fun theListGroupsEachEventUnderEveryDayItCoversLeavingOutEmptyDays() {
        val trip = allDay("Trip", "2026-11-25", "2026-11-26")
        val lunch = timed("Lunch", "2026-11-25", "05:00", "06:00")
        val call = timed("Call", "2026-11-28", "09:00", "09:30")
        val days = Agenda.group(listOf(call, lunch, trip), d("2026-11-23"), d("2026-12-06"))
        assertEquals(listOf(d("2026-11-25"), d("2026-11-26"), d("2026-11-28")), days.map { it.date })
        assertEquals(listOf("Trip", "Lunch"), days[0].items.map { it.title })
        assertEquals(listOf("Trip"), days[1].items.map { it.title })
    }

    @Test
    fun overlappingEventsSitSideBySide() {
        val a = timed("A", "2026-11-24", "09:00", "10:00")
        val b = timed("B", "2026-11-24", "09:30", "11:00")
        val c = timed("C", "2026-11-24", "10:00", "10:30")
        val e = timed("E", "2026-11-24", "12:00", "12:10")
        val placed = DayLayout.day(d("2026-11-24"), listOf(a, b, c, e), utc).associateBy { it.occurrence.title }
        assertEquals(0 to 2, placed.getValue("A").column to placed.getValue("A").columns)
        assertEquals(1 to 2, placed.getValue("B").column to placed.getValue("B").columns)
        assertEquals(0 to 2, placed.getValue("C").column to placed.getValue("C").columns)
        assertEquals(0 to 1, placed.getValue("E").column to placed.getValue("E").columns)
        // Ten minutes is drawn as half an hour, to be tapped.
        assertEquals(12 * 60 + DayLayout.MIN_MINUTES, placed.getValue("E").endMin)
    }

    @Test
    fun anEventRunningPastMidnightIsOnBothDaysGrids() {
        val night = occurrence(Instant.parse("2026-11-24T22:00:00Z"), Instant.parse("2026-11-25T02:00:00Z"), d("2026-11-24"), "Night", lastDay = d("2026-11-25"))
        assertEquals(22 * 60 to 24 * 60, DayLayout.day(d("2026-11-24"), listOf(night), utc).single().let { it.startMin to it.endMin })
        assertEquals(0 to 2 * 60, DayLayout.day(d("2026-11-25"), listOf(night), utc).single().let { it.startMin to it.endMin })
    }
}

/** clock-minutes.test.ts: an event on the grid of a day the clocks change. */
class GridOnClockChangeDaysTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val spring = d("2026-10-04")
    private val fall = d("2027-04-04")

    private fun at(date: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(date.atTime(h, m), sydney)

    @Test
    fun anEventIsDrawnAt1700OnBothChangeDays() {
        for (day in listOf(spring, fall)) {
            val o = occurrence(at(day, 17).toInstant(), at(day, 21, 30).toInstant(), day)
            val placed = DayLayout.day(day, listOf(o), sydney).single()
            assertEquals(17 * 60 to 21 * 60 + 30, placed.startMin to placed.endMin)
        }
    }

    @Test
    fun anEventEndingAtMidnightRunsToTheBottom() {
        val o = occurrence(at(spring, 22).toInstant(), spring.plusDays(1).atStartOfDay(sydney).toInstant(), spring)
        val placed = DayLayout.day(spring, listOf(o), sydney).single()
        assertEquals(22 * 60 to 24 * 60, placed.startMin to placed.endMin)
    }
}

internal fun occurrence(
    start: java.time.Instant,
    end: java.time.Instant,
    day: LocalDate,
    title: String = "Pizza",
    allDay: Boolean = false,
    lastDay: LocalDate = day,
    id: String = "r1",
) = Occurrence(id, "c1", "u-$id", null, title, "", "", allDay, start, end, day, lastDay, false, false, null)
