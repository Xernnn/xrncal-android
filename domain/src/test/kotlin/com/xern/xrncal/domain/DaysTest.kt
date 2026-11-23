package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun d(s: String) = LocalDate.parse(s)

/** The laptop app's all-day-end.test.ts and all-day-covers-date.test.ts, on dates. */
class AllDayTest {
    @Test
    fun anExclusiveEndBecomesTheLastDayCovered() {
        assertEquals(d("2026-09-14"), AllDay.lastDay(d("2026-09-14"), d("2026-09-15")))
        assertEquals(d("2026-09-03"), AllDay.lastDay(d("2026-09-01"), d("2026-09-04")))
        // Zero length, and an end before the start: a single day.
        assertEquals(d("2026-09-14"), AllDay.lastDay(d("2026-09-14"), d("2026-09-14")))
        assertEquals(d("2026-09-14"), AllDay.lastDay(d("2026-09-14"), d("2026-09-10")))
    }

    @Test
    fun theLastDayBecomesTheExclusiveEndAgain() {
        assertEquals(d("2026-09-15"), AllDay.exclusiveEnd(d("2026-09-14"), d("2026-09-14")))
        assertEquals(d("2026-09-04"), AllDay.exclusiveEnd(d("2026-09-01"), d("2026-09-03")))
        // Never at or before the start.
        assertEquals(d("2026-09-15"), AllDay.exclusiveEnd(d("2026-09-14"), d("2026-09-10")))
    }

    @Test
    fun roundTripsWithoutDrifting() {
        val start = d("2026-09-14")
        assertEquals(d("2026-09-15"), AllDay.exclusiveEnd(start, AllDay.lastDay(start, d("2026-09-15"))))
    }

    @Test
    fun coversTheDaysOfItsSpanOnly() {
        assertTrue(AllDay.covers(d("2026-09-14"), d("2026-09-14"), d("2026-09-14")))
        assertFalse(AllDay.covers(d("2026-09-13"), d("2026-09-13"), d("2026-09-14")))
        assertFalse(AllDay.covers(d("2026-09-15"), d("2026-09-15"), d("2026-09-14")))
        val (s, e) = d("2026-09-14") to d("2026-09-17")
        assertEquals(listOf(false, true, true, true, false), listOf("13", "14", "16", "17", "18").map { AllDay.covers(s, e, d("2026-09-$it")) })
        assertTrue(AllDay.covers(d("2026-09-29"), d("2026-10-02"), d("2026-10-01")))
        assertFalse(AllDay.covers(d("2026-09-29"), d("2026-10-02"), d("2026-10-03")))
    }

    @Test
    fun walksEveryDayOfASpan() {
        assertEquals(listOf(d("2026-09-14")), AllDay.days(d("2026-09-14"), d("2026-09-14")))
        assertEquals((14..17).map { d("2026-09-$it") }, AllDay.days(d("2026-09-14"), d("2026-09-17")))
        assertEquals(listOf(d("2026-09-29"), d("2026-09-30"), d("2026-10-01")), AllDay.days(d("2026-09-29"), d("2026-10-01")))
        assertEquals(listOf(d("2026-09-14")), AllDay.days(d("2026-09-14"), d("2026-09-10")))
    }

    @Test
    fun capsACorruptSpan() {
        val days = AllDay.days(d("2026-01-01"), d("2999-01-01"))
        assertTrue(days.size <= 366)
        assertEquals(d("2026-01-01"), days.first())
    }

    @Test
    fun walksTheScreenForAnEventLongerThanAYear() {
        val shown = d("2026-10-26")..d("2026-12-06")
        val days = AllDay.days(d("2024-01-01"), d("2027-12-31"), shown)
        assertEquals(42, days.size)
        assertEquals(listOf(d("2026-10-26"), d("2026-12-06")), listOf(days.first(), days.last()))
        assertEquals(listOf(d("2026-10-26"), d("2026-10-27"), d("2026-10-28")), AllDay.days(d("2025-01-01"), d("2026-10-28"), shown))
        assertEquals(listOf(d("2026-12-05"), d("2026-12-06")), AllDay.days(d("2026-12-05"), d("2031-01-01"), shown))
        assertEquals(365, AllDay.days(LocalDate.of(1, 1, 1), d("9999-12-31"), d("2026-01-01")..d("2026-12-31")).size)
    }
}

/** event-length.test.ts */
class EventLengthTest {
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun isAnHourUntilAnotherLengthIsChosen() {
        assertEquals(60, EventLength.DEFAULT)
        assertEquals(listOf(15, 30, 45, 60, 90, 120), EventLength.OPTIONS)
        for (o in EventLength.OPTIONS) assertEquals(o, EventLength.minutes(o))
        for (stored in listOf(0, -30, 20, 600, Double.NaN, "30", null, true)) assertEquals(60, EventLength.minutes(stored))
    }

    @Test
    fun endsThatLongAfterItsStart() {
        val start = ZonedDateTime.of(2026, 11, 12, 9, 0, 0, 0, hcm)
        fun clock(setting: Any?) = EventLength.end(start, setting).toLocalDateTime().toString()
        assertEquals("2026-11-12T09:15", clock(15))
        assertEquals("2026-11-12T09:45", clock(45))
        assertEquals("2026-11-12T10:00", clock(60))
        assertEquals("2026-11-12T10:30", clock(90))
        assertEquals("2026-11-12T11:00", clock(120))
        assertEquals("2026-11-12T10:00", clock("nonsense"))
    }

    @Test
    fun runsPastMidnightAndAlwaysEndsAfterItStarts() {
        val late = ZonedDateTime.of(2026, 11, 12, 23, 30, 0, 0, hcm)
        assertEquals("2026-11-13T00:30", EventLength.end(late, 60).toLocalDateTime().toString())
        assertEquals("2026-11-13T01:30", EventLength.end(late, 120).toLocalDateTime().toString())
        assertEquals("2026-11-12T23:45", EventLength.end(late, 15).toLocalDateTime().toString())
        for (s in EventLength.OPTIONS + listOf(0, -5, "x")) assertTrue(EventLength.end(late, s).isAfter(late))
    }
}

/** clock-minutes.test.ts: Sydney springs forward on 4 Oct 2026 and falls back on 4 Apr 2027. */
class ClockMinutesTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val spring = d("2026-10-04")
    private val fall = d("2027-04-04")

    private fun at(date: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(date.atTime(h, m), sydney)

    @Test
    fun reads1700AsRow17OnBothClockChangeDays() {
        for (day in listOf(spring, fall)) assertEquals(17 * 60, ClockMinutes.ofDay(at(day, 17), day))
    }

    @Test
    fun clampsToTheDay() {
        assertEquals(0, ClockMinutes.ofDay(spring.atStartOfDay(sydney).minusHours(3), spring))
        assertEquals(0, ClockMinutes.ofDay(spring.atStartOfDay(sydney), spring))
        assertEquals(24 * 60, ClockMinutes.ofDay(spring.plusDays(1).atStartOfDay(sydney), spring))
        assertEquals(24 * 60, ClockMinutes.ofDay(spring.plusDays(1).atStartOfDay(sydney).minusNanos(1_000_000), spring))
    }

    @Test
    fun puts1000At1000OnTheSpringForwardDay() {
        assertEquals("2026-10-04T10:00", ClockMinutes.at(spring, 600, sydney).toLocalDateTime().toString())
        assertEquals("2027-04-05T00:00", ClockMinutes.at(fall, 1440, sydney).toLocalDateTime().toString())
    }

    @Test
    fun roundTripsThroughTheDay() {
        for (m in listOf(0, 90, 6 * 60, 17 * 60 + 30, 23 * 60 + 45)) assertEquals(m, ClockMinutes.ofDay(ClockMinutes.at(spring, m, sydney), spring))
    }
}

/** week-start.ts */
class WeekStartTest {
    @Test
    fun startsTheWeekOnTheChosenDay() {
        val wed = d("2026-11-25")
        assertEquals(d("2026-11-23"), WeekStart.startOfWeek(wed, DayOfWeek.MONDAY))
        assertEquals(d("2026-11-22"), WeekStart.startOfWeek(wed, DayOfWeek.SUNDAY))
        assertEquals(d("2026-11-21"), WeekStart.startOfWeek(wed, DayOfWeek.SATURDAY))
        assertEquals(DayOfWeek.SUNDAY, WeekStart.order(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, WeekStart.order(DayOfWeek.SUNDAY).last())
    }

    @Test
    fun numbersARowByItsThursday() {
        // A Sunday-first row from 27 Dec 2026 holds Thursday 31 Dec: ISO week 53 of 2026.
        assertEquals(53, WeekStart.isoWeekOfRow(d("2026-12-27")))
        assertEquals(1, WeekStart.isoWeekOfRow(d("2027-01-04")))
    }
}

/** text-fold.ts */
class TextFoldTest {
    @Test
    fun findsWordsWhateverTheirAccents() {
        assertEquals("ca phe sua da", TextFold.forMatch("Cà phê sữa đá!"))
        assertEquals("room 3", TextFold.forMatch("(Room 3)"))
        assertEquals("12 le loi q1", TextFold.forMatch("12 Lê Lợi, Q1"))
        assertTrue(TextFold.matches(TextFold.forMatch("Giỗ ông nội"), "gio ong"))
        assertTrue(TextFold.matches(TextFold.forMatch("Đà Nẵng trip"), "da nang"))
        assertFalse(TextFold.matches(TextFold.forMatch("Standup"), "tand"))
    }
}
