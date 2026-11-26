package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.ICalParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "This and following" (Scope.FOLLOWING): a series split at a date, as the laptop splits it
 * (xrncal-desktop's series-split and recurring-scope tests). The dates before stay as they were.
 */
class SeriesSplitTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-11-24T02:00:00Z")

    private fun weekly(vararg extra: String, rule: String = "FREQ=WEEKLY;BYDAY=MO") = ics(
        vevent(
            "UID:standup-1", "SUMMARY:Standup", "DTSTART;TZID=Australia/Sydney:20261005T090000", "DTEND;TZID=Australia/Sydney:20261005T091500",
            "RRULE:$rule", "X-OTHER-APP:42", *extra,
        ),
    )

    /** Mondays from 5 Oct: 19 Oct and 9 Nov deleted, 12 Oct moved to 11:00 and 2 Nov to 12:00. */
    private val standup = weekly("EXDATE;TZID=Australia/Sydney:20261019T090000,20261109T090000").let {
        it.replace(
            "END:VCALENDAR",
            vevent("UID:standup-1", "RECURRENCE-ID;TZID=Australia/Sydney:20261012T090000", "SUMMARY:Standup (late)", "DTSTART;TZID=Australia/Sydney:20261012T110000", "DTEND;TZID=Australia/Sydney:20261012T111500") +
                vevent("UID:standup-1", "RECURRENCE-ID;TZID=Australia/Sydney:20261102T090000", "SUMMARY:Standup (lunch)", "DTSTART;TZID=Australia/Sydney:20261102T120000", "DTEND;TZID=Australia/Sydney:20261102T121500") +
                "END:VCALENDAR",
        )
    }

    private fun occ(text: String, day: String) = expand(text, day, day, sydney).single { it.title.startsWith("Standup") }

    private fun draftAt(text: String, day: String) = Edits.draftOf(ParsedResource.parse(text, sydney), occ(text, day), sydney)

    private fun dates(text: String, from: String = "2026-10-01", to: String = "2026-12-31") =
        expand(text, from, to, sydney).map { "${it.firstDay} " + it.start.atZone(sydney).toLocalTime() }

    @Test
    fun aNewTimeForThisAndFollowingLeavesTheDatesBeforeAsTheyWere() {
        val d = draftAt(standup, "2026-10-26").copy(startTime = LocalTime.parse("10:00"), endTime = LocalTime.parse("10:15"))
        val (before, after) = Edits.split(standup, occ(standup, "2026-10-26"), d, "rest-1", now)!!
        assertEquals(listOf("2026-10-05 09:00", "2026-10-12 11:00"), dates(before))
        assertTrue(before, before.contains("RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261025T215959Z"))
        assertTrue(!before.contains("Standup (lunch)") && !before.contains("20261109"))
        assertEquals(
            listOf("2026-10-26 10:00", "2026-11-02 12:00", "2026-11-16 10:00", "2026-11-23 10:00", "2026-11-30 10:00", "2026-12-07 10:00"),
            dates(after, to = "2026-12-07"),
        )
        // A new event: its own UID on the series and its changed date, everything else kept.
        val events = ICalParser.parse(after).children("VEVENT")
        assertEquals(listOf("rest-1", "rest-1"), events.map { it.prop("UID")!!.value })
        assertTrue(after.contains("RECURRENCE-ID;TZID=Australia/Sydney:20261102T100000"))
        assertTrue(after.contains("EXDATE;TZID=Australia/Sydney:20261109T100000"))
        assertTrue(after.contains("X-OTHER-APP:42") && before.contains("X-OTHER-APP:42"))
    }

    @Test
    fun aSeriesOfFourSplitAtItsThirdDateStillHasFour() {
        val four = weekly(rule = "FREQ=WEEKLY;COUNT=4")
        val (before, after) = Edits.split(four, occ(four, "2026-10-19"), draftAt(four, "2026-10-19").copy(title = "Standup (new room)"), "rest-1", now)!!
        assertEquals(listOf("2026-10-05 09:00", "2026-10-12 09:00"), dates(before))
        assertEquals(listOf("2026-10-19 09:00", "2026-10-26 09:00"), dates(after))
        assertTrue(after, after.contains("COUNT=2"))
    }

    @Test
    fun aDeletedDateCountsAsOneOfTheSeriesAsTheRuleCountsIt() {
        val four = weekly("EXDATE;TZID=Australia/Sydney:20261012T090000", rule = "FREQ=WEEKLY;COUNT=4")
        val (before, after) = Edits.split(four, occ(four, "2026-10-19"), draftAt(four, "2026-10-19"), "rest-1", now)!!
        assertEquals(listOf("2026-10-05 09:00"), dates(before))
        assertEquals(listOf("2026-10-19 09:00", "2026-10-26 09:00"), dates(after))
    }

    @Test
    fun aSeriesThatEndsOnADateStillEndsOnIt() {
        val ending = weekly(rule = "FREQ=WEEKLY;UNTIL=20261102T125959Z")
        val (_, after) = Edits.split(ending, occ(ending, "2026-10-19"), draftAt(ending, "2026-10-19").copy(startTime = LocalTime.parse("08:00"), endTime = LocalTime.parse("08:15")), "rest-1", now)!!
        assertEquals(listOf("2026-10-19 08:00", "2026-10-26 08:00", "2026-11-02 08:00"), dates(after))
    }

    @Test
    fun theRestTakesTheRuleTheEditorChoseOrNone() {
        val chosen = Edits.split(standup, occ(standup, "2026-10-26"), draftAt(standup, "2026-10-26").copy(rrule = "FREQ=DAILY;COUNT=3"), "rest-1", now)!!.second
        assertEquals(listOf("2026-10-26 09:00", "2026-10-27 09:00", "2026-10-28 09:00"), dates(chosen))
        val once = Edits.split(standup, occ(standup, "2026-10-26"), draftAt(standup, "2026-10-26").copy(rrule = null), "rest-1", now)!!.second
        assertEquals(listOf("2026-10-26 09:00"), dates(once))
        assertEquals(1, ICalParser.parse(once).children("VEVENT").size)
    }

    @Test
    fun theNamedWeekdayMovesWithADateMovedToAnotherDay() {
        val d = draftAt(standup, "2026-10-26").let { it.copy(startDate = LocalDate.parse("2026-10-27"), endDate = LocalDate.parse("2026-10-27")) }
        val (before, after) = Edits.split(standup, occ(standup, "2026-10-26"), d, "rest-1", now)!!
        assertEquals(listOf("2026-10-05 09:00", "2026-10-12 11:00"), dates(before))
        assertTrue(after, after.contains("BYDAY=TU"))
        assertEquals(listOf("2026-10-27 09:00", "2026-11-02 12:00", "2026-11-17 09:00"), dates(after, to = "2026-11-17"))
    }

    @Test
    fun fromTheFirstDateItIsTheWholeSeries() {
        assertNull(Edits.split(standup, occ(standup, "2026-10-05"), draftAt(standup, "2026-10-05"), "rest-1", now))
    }

    @Test
    fun anAllDaySeriesEndsTheDayBefore() {
        val daily = ics(vevent("UID:walk-1", "SUMMARY:Standup walk", "DTSTART;VALUE=DATE:20261101", "DTEND;VALUE=DATE:20261102", "RRULE:FREQ=DAILY"))
        val (before, after) = Edits.split(daily, occ(daily, "2026-11-05"), draftAt(daily, "2026-11-05"), "rest-1", now)!!
        assertTrue(before, before.contains("RRULE:FREQ=DAILY;UNTIL=20261104"))
        assertEquals(4, expand(before, "2026-11-01", "2026-11-30", sydney).size)
        assertEquals(LocalDate.parse("2026-11-05"), expand(after, "2026-11-01", "2026-11-30", sydney).first().firstDay)
        assertEquals(26, expand(after, "2026-11-01", "2026-11-30", sydney).size)
    }

    @Test
    fun deletingThisAndFollowingEndsTheSeriesTheMomentBefore() {
        val text = Edits.delete(standup, occ(standup, "2026-10-26"), Scope.FOLLOWING, now)!!
        assertEquals(listOf("2026-10-05 09:00", "2026-10-12 11:00"), dates(text))
        assertEquals(2, ICalParser.parse(text).children("VEVENT").size)
        // From the first date: the whole event.
        assertNull(Edits.delete(standup, occ(standup, "2026-10-05"), Scope.FOLLOWING, now))
    }

    @Test
    fun aRuleFollowsAStartMovedToAnotherDayAsTheLaptopsDoes() {
        val monday = LocalDate.parse("2026-09-07")
        fun moved(rule: String, to: String) = RepeatRules.forMovedStart(rule, monday, LocalDate.parse(to))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", moved("FREQ=WEEKLY;BYDAY=MO", "2026-09-07"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", moved("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "2026-09-08"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", moved("FREQ=WEEKLY;BYDAY=MO,WE", "2026-09-09"))
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", moved("FREQ=WEEKLY;BYDAY=MO,WE", "2026-09-08"))
        assertEquals("FREQ=WEEKLY;BYDAY=SU", moved("FREQ=WEEKLY;BYDAY=MO", "2026-09-06"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR;COUNT=5", moved("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=5", "2026-09-11"))
        assertEquals("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", moved("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", "2026-09-08"))
        val fifteenth = LocalDate.parse("2026-09-15")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=16", RepeatRules.forMovedStart("FREQ=MONTHLY;BYMONTHDAY=15", fifteenth, LocalDate.parse("2026-09-16")))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=3,17", RepeatRules.forMovedStart("FREQ=MONTHLY;BYMONTHDAY=1,15", fifteenth, LocalDate.parse("2026-09-17")))
        assertEquals("FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=2", RepeatRules.forMovedStart("FREQ=YEARLY;BYMONTH=9;BYMONTHDAY=15", fifteenth, LocalDate.parse("2026-10-02")))
        assertEquals("FREQ=WEEKLY", moved("FREQ=WEEKLY", "2026-09-08"))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", moved("FREQ=WEEKLY;BYDAY=WE", "2026-09-08"))
        assertEquals("FREQ=MONTHLY;BYDAY=2MO", moved("FREQ=MONTHLY;BYDAY=2MO", "2026-09-08"))
    }
}
