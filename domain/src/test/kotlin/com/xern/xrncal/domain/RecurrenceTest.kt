package com.xern.xrncal.domain

import java.time.LocalDate
import java.time.LocalDateTime
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The laptop app's bounded-rrule.test.ts. The bounded walk must give exactly what lib-recur's own
 * walk from the first date gives, for every real rule: it only starts later and stops sooner.
 */
class RecurrenceTest {
    private val rules = listOf(
        "FREQ=DAILY", "FREQ=DAILY;INTERVAL=3", "FREQ=DAILY;BYDAY=MO,WE,FR", "FREQ=DAILY;BYHOUR=8,17;BYMINUTE=0,30",
        "FREQ=WEEKLY", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH", "FREQ=WEEKLY;INTERVAL=3;WKST=SU;BYDAY=SU,SA",
        "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "FREQ=MONTHLY", "FREQ=MONTHLY;INTERVAL=5", "FREQ=MONTHLY;BYMONTHDAY=31",
        "FREQ=MONTHLY;BYMONTHDAY=-1", "FREQ=MONTHLY;BYDAY=-1FR", "FREQ=MONTHLY;INTERVAL=2;BYDAY=2TU,4TU",
        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1", "FREQ=YEARLY", "FREQ=YEARLY;INTERVAL=4", "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH",
        "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", "FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO", "FREQ=YEARLY;BYYEARDAY=100,-1",
        "FREQ=YEARLY;BYMONTH=1", "FREQ=YEARLY;BYMONTH=3", "FREQ=YEARLY;BYMONTH=6;BYMONTHDAY=16", "FREQ=YEARLY;INTERVAL=3;BYMONTH=1;BYMONTHDAY=-1",
        "FREQ=YEARLY;BYMONTH=9;BYDAY=1MO", "FREQ=YEARLY;INTERVAL=2;BYMONTH=5;BYDAY=-1SU", "FREQ=YEARLY;BYMONTH=6;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
        "FREQ=YEARLY;BYMONTH=12;BYDAY=SA,SU;BYHOUR=9,18", "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;COUNT=6", "FREQ=YEARLY;COUNT=40",
        "FREQ=YEARLY;UNTIL=20290101T000000", "FREQ=YEARLY;BYMONTH=1,7", "FREQ=WEEKLY;UNTIL=20261015T000000", "FREQ=DAILY;UNTIL=20200101T000000",
        "FREQ=WEEKLY;COUNT=1000", "FREQ=MONTHLY;COUNT=360",
    )
    private val firstDates = listOf(LocalDateTime.of(2001, 1, 31, 9, 30, 15), LocalDateTime.of(2004, 2, 29, 23, 0), LocalDateTime.of(2019, 6, 16, 0, 0))
    private val ranges = listOf(
        LocalDateTime.of(2026, 10, 1, 0, 0) to LocalDateTime.of(2026, 11, 1, 0, 0),
        LocalDateTime.of(2026, 12, 28, 0, 0) to LocalDateTime.of(2027, 1, 4, 0, 0),
        LocalDateTime.of(2028, 1, 1, 0, 0) to LocalDateTime.of(2030, 12, 31, 23, 59, 59),
        LocalDateTime.of(2019, 6, 1, 0, 0) to LocalDateTime.of(2019, 7, 1, 0, 0),
    )

    /** lib-recur's own walk from the first date, every date looked at. */
    private fun reference(rule: String, start: LocalDateTime, from: LocalDateTime, to: LocalDateTime): List<LocalDateTime> {
        val it = RecurrenceRule(rule).iterator(DateTime(start.year, start.monthValue - 1, start.dayOfMonth, start.hour, start.minute, start.second))
        val out = mutableListOf<LocalDateTime>()
        while (it.hasNext()) {
            val d = it.nextDateTime()
            val t = LocalDateTime.of(d.year, d.month + 1, d.dayOfMonth, d.hours, d.minutes, d.seconds)
            if (t.isAfter(to)) break
            if (!t.isBefore(from)) out += t
        }
        return out
    }

    @Test
    fun givesWhatLibRecursOwnWalkGives() {
        for (rule in rules) for (start in firstDates) for ((from, to) in ranges) {
            assertEquals("$rule from $start in $from..$to", reference(rule, start, from, to), Recurrence.between(rule, start, false, from, to))
        }
    }

    private fun spent(rule: String, start: LocalDateTime, from: LocalDateTime, to: LocalDateTime): Pair<List<LocalDateTime>, Long> {
        val budget = Recurrence.Budget(1_000_000_000_000L, Int.MAX_VALUE)
        val dates = Recurrence.between(rule, start, false, from, to, budget)
        return dates to 1_000_000_000_000L - budget.remaining
    }

    private val october = LocalDateTime.of(2026, 10, 1, 0, 0) to LocalDateTime.of(2026, 10, 31, 23, 59, 59)

    @Test
    fun walksOnlyThisMonthOfADailySeriesFromTheYear1000() {
        val (dates, work) = spent("FREQ=DAILY", LocalDateTime.of(1000, 1, 1, 9, 0), october.first, october.second)
        assertEquals(31, dates.size)
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0), dates.first())
        assertTrue(work < 2_000)
    }

    @Test
    fun startsTheWalkNearTheRange() {
        for (rule in listOf("FREQ=WEEKLY;INTERVAL=3;WKST=SU;BYDAY=SU,SA", "FREQ=MONTHLY;BYDAY=-1FR", "FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO")) {
            assertTrue(rule, spent(rule, firstDates[0], october.first, october.second).second < 10_000)
        }
    }

    @Test
    fun aBirthdayCostsLittle() {
        val (_, birthday) = spent("FREQ=YEARLY", LocalDateTime.of(1975, 5, 15, 0, 0), LocalDateTime.of(2026, 11, 30, 0, 0), LocalDateTime.of(2027, 1, 10, 23, 59))
        assertTrue(birthday < 1_000)
        val (leap, cost) = spent("FREQ=YEARLY", LocalDateTime.of(1996, 2, 29, 0, 0), LocalDateTime.of(2028, 2, 28, 0, 0), LocalDateTime.of(2028, 4, 9, 23, 59))
        assertEquals(listOf(LocalDateTime.of(2028, 2, 29, 0, 0)), leap)
        assertTrue(cost < 2_000)
    }

    @Test
    fun givesUpOnARuleThatCanNeverMatch() {
        val (dates, work) = spent("FREQ=DAILY;BYMONTH=2;BYMONTHDAY=30", LocalDateTime.of(2026, 1, 1, 9, 0), october.first, october.second)
        // DTSTART is always the first date (RFC 5545); nothing else ever matches.
        assertEquals(emptyList<LocalDateTime>(), dates)
        assertTrue(work < 10_000)
    }

    @Test
    fun keepsWhatItFoundWhenTheWalkPastTheRangeRunsOut() {
        val (dates, _) = spent("FREQ=DAILY;BYMONTH=2;BYMONTHDAY=29", LocalDateTime.of(2001, 1, 1, 9, 0), LocalDateTime.of(2028, 2, 1, 0, 0), LocalDateTime.of(2028, 3, 1, 0, 0))
        assertEquals(listOf(LocalDateTime.of(2028, 2, 29, 9, 0)), dates)
    }

    @Test
    fun capsACountSeriesWhichWalksFromItsFirstDate() {
        val (_, work) = spent("FREQ=DAILY;COUNT=1000000000", LocalDateTime.of(1000, 1, 1, 9, 0), october.first, october.second)
        assertTrue(work < 1_000_000)
    }

    @Test
    fun doesNotExpandARuleMoreOftenThanDaily() {
        val start = LocalDateTime.of(2026, 1, 1, 3, 0)
        for (rule in listOf("FREQ=SECONDLY", "FREQ=MINUTELY;INTERVAL=5", "FREQ=HOURLY", "FREQ=HOURLY;INTERVAL=24;BYHOUR=5")) {
            assertThrows(rule, Recurrence.Unexpandable::class.java) { Recurrence.between(rule, start, false, october.first, october.second) }
        }
    }

    @Test
    fun doesNotExpandTooManyTimesADayOrAnOverlongRule() {
        val start = LocalDateTime.of(2026, 1, 1, 0, 0)
        val halfHourly = "FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")};BYMINUTE=0,30"
        val overlong = "FREQ=YEARLY;BYSETPOS=${(1..200).joinToString(",")}"
        assertThrows(Recurrence.Unexpandable::class.java) { Recurrence.between(halfHourly, start, false, october.first, october.second) }
        assertThrows(Recurrence.Unexpandable::class.java) { Recurrence.between(overlong, start, false, october.first, october.second) }
    }

    private val oct = LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31)

    @Test
    fun aCalendarStopsSpendingOnceCostlyRulesHaveUsedItsAllowance() {
        val budget = Recurrence.budget(oct.first, oct.second)
        assertTrue(budget.remaining <= 5_000_000)
        var last = emptyList<LocalDateTime>()
        repeat(100) { last = Recurrence.between("FREQ=DAILY;COUNT=1000000000", LocalDateTime.of(1000, 1, 1, 9, 0), false, october.first, october.second, budget) }
        assertEquals(0L, budget.remaining)
        assertEquals(emptyList<LocalDateTime>(), last)
    }

    @Test
    fun holdsFarMoreOrdinarySeriesThanAnyoneKeeps() {
        val budget = Recurrence.budget(oct.first, oct.second)
        repeat(1_000) { assertEquals(31, Recurrence.between("FREQ=DAILY", LocalDateTime.of(2026, 1, 1, 9, 0), false, october.first, october.second, budget).size) }
        assertTrue(budget.remaining > 0)
    }

    @Test
    fun holdsACalendarOfFiveThousandBirthdays() {
        val budget = Recurrence.budget(oct.first, oct.second)
        var found = 0
        for (i in 0 until 5_000) {
            val birth = LocalDate.of(1950 + i % 56, 1, 1).plusDays((i % 365).toLong()).atStartOfDay()
            found += Recurrence.between("FREQ=YEARLY", birth, true, october.first, october.second, budget).size
        }
        val expected = (0 until 5_000).count { i -> LocalDate.of(1950 + i % 56, 1, 1).plusDays((i % 365).toLong()).monthValue == 10 }
        assertEquals(expected, found)
        assertTrue(budget.remaining > 0)
    }

    @Test
    fun aCalendarProducesAtMostItsShareOfDates() {
        val from = LocalDate.of(2026, 9, 28)
        val to = LocalDate.of(2026, 11, 8)
        val hourly = "FREQ=DAILY;BYHOUR=${(0..23).joinToString(",")}"
        val budget = Recurrence.budget(from, to)
        var found = 0
        repeat(200) { found += Recurrence.between(hourly, LocalDateTime.of(2026, 1, 1, 0, 0), false, from.atStartOfDay(), to.atTime(23, 59, 59), budget).size }
        assertEquals(Recurrence.CALENDAR_MAX_OCCURRENCES, found)
        assertTrue(budget.capped)

        val fewer = Recurrence.budget(from, to)
        var n = 0
        repeat(1_000) { n += Recurrence.between("FREQ=DAILY", LocalDateTime.of(2026, 1, 1, 0, 0), false, from.atStartOfDay(), to.atTime(23, 59, 59), fewer).size }
        assertEquals(1_000 * 42, n)
        assertFalse(fewer.capped)
    }

    @Test
    fun movesAUtcUntilIntoWallClockAndADateUntilToTheEndOfItsDay() {
        assertEquals("FREQ=DAILY;UNTIL=20260710T085959", Recurrence.wallClockUntil("FREQ=DAILY;UNTIL=20260710T125959Z", false) { it.minusHours(4) })
        assertEquals("FREQ=DAILY;UNTIL=20260710T235959", Recurrence.wallClockUntil("FREQ=DAILY;UNTIL=20260710", false) { it })
        assertEquals("FREQ=DAILY;UNTIL=20260709", Recurrence.wallClockUntil("FREQ=DAILY;UNTIL=20260709T090000Z", true) { it })
    }
}
