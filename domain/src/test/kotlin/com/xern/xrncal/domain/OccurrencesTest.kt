package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun vevent(vararg lines: String) = "BEGIN:VEVENT\r\n" + lines.joinToString("\r\n") + "\r\nEND:VEVENT\r\n"
internal fun ics(vararg vevents: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + vevents.joinToString("") + "END:VCALENDAR\r\n"

internal fun expand(text: String, from: String, to: String, zone: ZoneId = ZoneOffset.UTC): List<Occurrence> =
    Occurrences.expand(ParsedResource.parse(text, zone), "r1", "c1", LocalDate.parse(from), LocalDate.parse(to), zone)

/** The laptop app's expand-occurrences.test.ts, daylight-saving.test.ts and recurrence-until-and-zones.test.ts. */
class OccurrencesTest {
    private val standup = arrayOf("UID:uid-1", "SUMMARY:Team Standup", "DTSTART:20260803T090000Z", "DTEND:20260803T093000Z")

    private fun starts(list: List<Occurrence>) = list.map { it.start.toString() }

    @Test
    fun aSingleEventShowsInItsWindowOnly() {
        val one = expand(ics(vevent(*standup)), "2026-08-01", "2026-08-09")
        assertEquals(listOf("2026-08-03T09:00:00Z"), starts(one))
        assertEquals("Team Standup", one[0].title)
        assertEquals(false, one[0].recurring)
        assertEquals(emptyList<Occurrence>(), expand(ics(vevent(*standup)), "2026-08-10", "2026-08-19"))
    }

    @Test
    fun aWeeklySeriesShowsEveryWeek() {
        val occ = expand(ics(vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO")), "2026-08-01", "2026-08-31")
        assertEquals((listOf("03", "10", "17", "24", "31")).map { "2026-08-${it}T09:00:00Z" }, starts(occ))
        assertTrue(occ.all { it.recurring })
    }

    @Test
    fun aCountEndsTheSeries() {
        assertEquals(3, expand(ics(vevent(*standup, "RRULE:FREQ=WEEKLY;COUNT=3")), "2026-08-01", "2026-08-31").size)
    }

    @Test
    fun exdatesAreLeftOut() {
        val occ = expand(ics(vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE:20260810T090000Z,20260824T090000Z")), "2026-08-01", "2026-08-31")
        assertEquals(listOf("2026-08-03T09:00:00Z", "2026-08-17T09:00:00Z", "2026-08-31T09:00:00Z"), starts(occ))
    }

    @Test
    fun anOverrideShowsAsItSays() {
        val text = ics(
            vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO"),
            vevent("UID:uid-1", "RECURRENCE-ID:20260810T090000Z", "SUMMARY:Rescheduled Special Standup", "DTSTART:20260810T140000Z", "DTEND:20260810T144500Z"),
        )
        val occ = expand(text, "2026-08-01", "2026-08-15")
        assertEquals(listOf("Team Standup", "Rescheduled Special Standup"), occ.map { it.title })
        assertEquals("2026-08-10T14:00:00Z", occ[1].start.toString())
        assertEquals("2026-08-10T14:45:00Z", occ[1].end.toString())
        assertTrue(occ[1].exception)
        assertEquals("20260810T090000Z", occ[1].recurrenceId!!.value())
    }

    @Test
    fun aCancelledOverrideHidesItsDate() {
        val text = ics(
            vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO"),
            vevent("UID:uid-1", "RECURRENCE-ID:20260817T090000Z", "STATUS:CANCELLED", "DTSTART:20260817T090000Z", "DTEND:20260817T093000Z"),
        )
        val occ = expand(text, "2026-08-01", "2026-08-31")
        assertEquals(4, occ.size)
        assertTrue("2026-08-17T09:00:00Z" !in starts(occ))
    }

    @Test
    fun anOverrideMovedIntoTheRangeShowsAndOneMovedOutDoesNot() {
        val text = ics(
            vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO"),
            vevent("UID:uid-1", "RECURRENCE-ID:20260824T090000Z", "SUMMARY:Pulled forward", "DTSTART:20260812T090000Z", "DTEND:20260812T093000Z"),
            vevent("UID:uid-1", "RECURRENCE-ID:20260810T090000Z", "SUMMARY:Pushed back", "DTSTART:20260825T090000Z", "DTEND:20260825T093000Z"),
        )
        val occ = expand(text, "2026-08-09", "2026-08-15")
        assertEquals(listOf("Pulled forward"), occ.map { it.title })
    }

    @Test
    fun rdatesAddDatesAndADateExdateTakesADayOut() {
        val text = ics(vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=3", "RDATE:20260805T090000Z", "EXDATE;VALUE=DATE:20260810"))
        assertEquals(listOf("2026-08-03T09:00:00Z", "2026-08-05T09:00:00Z", "2026-08-17T09:00:00Z"), starts(expand(text, "2026-08-01", "2026-08-31")))
    }

    @Test
    fun anOverrideWithoutItsSeriesShowsOnItsOwn() {
        // An invitation to one date of someone else's series.
        val text = ics(vevent("UID:theirs", "RECURRENCE-ID;TZID=Europe/Berlin:20261110T090000", "SUMMARY:Review", "DTSTART;TZID=Europe/Berlin:20261110T090000", "DTEND;TZID=Europe/Berlin:20261110T100000"))
        val occ = expand(text, "2026-11-01", "2026-11-30")
        assertEquals(listOf("2026-11-10T08:00:00Z"), starts(occ))
        assertEquals(false, occ[0].recurring)
    }

    /** A DATE is a day, not an instant: an all-day series repeats on its dates in any zone. */
    @Test
    fun anAllDaySeriesKeepsItsDatesSkipsAndMovedWeeks() {
        val text = ics(
            vevent("UID:weekly", "SUMMARY:Sanctuary", "DTSTART;VALUE=DATE:20251125", "DTEND;VALUE=DATE:20251126", "RRULE:FREQ=WEEKLY;BYDAY=TU", "EXDATE;VALUE=DATE:20251209"),
            vevent("UID:weekly", "RECURRENCE-ID;VALUE=DATE:20251216", "DTSTART;TZID=Australia/Sydney:20251215T110000", "DTEND;TZID=Australia/Sydney:20251215T120000"),
        )
        val occ = expand(text, "2025-11-20", "2025-12-30")
        assertEquals(
            listOf("2025-11-25" to true, "2025-12-02" to true, "2025-12-15" to false, "2025-12-23" to true, "2025-12-30" to true),
            occ.map { it.firstDay.toString() to it.allDay },
        )
        assertEquals("2025-12-15T00:00:00Z", occ[2].start.toString())
    }

    @Test
    fun allDayDatesStayOnTheirDayAcrossASummerTimeChange() {
        val sydney = ZoneId.of("Australia/Sydney")
        val text = ics(vevent("UID:w", "DTSTART;VALUE=DATE:20251125", "DTEND;VALUE=DATE:20251126", "RRULE:FREQ=WEEKLY;BYDAY=TU"))
        val occ = expand(text, "2026-03-30", "2026-04-19", sydney)
        assertEquals(listOf("2026-03-31", "2026-04-07", "2026-04-14"), occ.map { it.firstDay.toString() })
        assertTrue(occ.all { it.start == it.firstDay.atStartOfDay(sydney).toInstant() })
    }

    @Test
    fun aTimedSeriesInAZoneNothingNamesRepeatsInThePhonesZone() {
        val text = ics(vevent("UID:w", "DTSTART;TZID=floating:20251125T090000", "DTEND;TZID=floating:20251125T100000", "RRULE:FREQ=WEEKLY;BYDAY=TU"))
        assertEquals(3, expand(text, "2025-11-20", "2025-12-09").size)
    }

    private val feed = arrayOf("UID:feed-1", "SUMMARY:x", "DTSTART:20261005T090000Z", "DTEND:20261005T090100Z")

    @Test
    fun aSecondlySeriesShowsOnceAtItsStart() {
        assertEquals(listOf("2026-10-05T09:00:00Z"), starts(expand(ics(vevent(*feed, "RRULE:FREQ=SECONDLY")), "2026-10-01", "2026-10-31")))
    }

    @Test
    fun aRuleThatCanNeverMatchShowsOnlyItsStart() {
        // lib-recur counts DTSTART as the first date, as RFC 5545 says; nothing after it matches.
        assertEquals(listOf("2026-10-05T09:00:00Z"), starts(expand(ics(vevent(*feed, "RRULE:FREQ=DAILY;BYMONTH=2;BYMONTHDAY=30")), "2026-10-01", "2026-10-31")))
    }

    @Test
    fun doesNotGoBackCenturiesForDatesThatLastCenturies() {
        val endless = ics(vevent("UID:e", "DTSTART:19000101T090000Z", "DTEND:21000101T090000Z", "RRULE:FREQ=DAILY"))
        val occ = expand(endless, "2026-10-05", "2026-10-05")
        assertTrue(occ.size in 300..368)
        assertEquals("2026-10-05T09:00:00Z", occ.maxOf { it.start }.toString())
    }

    @Test
    fun stillShowsAYearlyEventOfSeveralMonthsPartWayThroughIt() {
        val term = ics(vevent("UID:t", "DTSTART:20200601T000000Z", "DTEND:20200915T000000Z", "RRULE:FREQ=YEARLY"))
        assertEquals(listOf("2026-06-01T00:00:00Z"), starts(expand(term, "2026-09-01", "2026-09-06")))
    }

    @Test
    fun stillFindsThisMonthOfADailySeriesFromTheYear1000() {
        val ancient = ics(vevent("UID:a", "DTSTART:10000101T090000Z", "DTEND:10000101T090100Z", "RRULE:FREQ=DAILY"))
        val occ = expand(ancient, "2026-10-01", "2026-10-31")
        assertEquals(31, occ.size)
        assertEquals("2026-10-31T09:00:00Z", occ[30].start.toString())
    }

    // ---- daylight-saving.test.ts: a series keeps its clock time, not its offset ----

    private fun weekly(local: String, zone: String, rule: String, minutes: Int = 30): String {
        val start = java.time.LocalDateTime.parse(local)
        val end = start.plusMinutes(minutes.toLong())
        val f = com.xern.xrncal.domain.ical.ICalTime.DATE_TIME
        return ics(vevent("UID:u1", "SUMMARY:Standup", "DTSTART;TZID=$zone:${start.format(f)}", "DTEND;TZID=$zone:${end.format(f)}", "RRULE:$rule"))
    }

    private fun clock(list: List<Occurrence>, zone: String) = list.map { it.start.atZone(ZoneId.of(zone)).toLocalTime().toString() }

    @Test
    fun holds0900ThroughTheSouthernSpringForward() {
        val occ = expand(weekly("2026-09-01T09:00", "Australia/Sydney", "FREQ=WEEKLY;BYDAY=TU"), "2026-09-01", "2026-10-31")
        assertTrue(occ.size > 6)
        assertEquals(setOf("09:00"), clock(occ, "Australia/Sydney").toSet())
    }

    @Test
    fun holds0900ThroughANorthernFallBack() {
        val occ = expand(weekly("2026-10-06T09:00", "America/New_York", "FREQ=WEEKLY;BYDAY=TU"), "2026-10-01", "2026-11-30")
        assertTrue(occ.size > 6)
        assertEquals(setOf("09:00"), clock(occ, "America/New_York").toSet())
    }

    @Test
    fun holdsAMonthlyEventThroughBothChangesInAYear() {
        val occ = expand(weekly("2026-01-05T08:30", "Europe/London", "FREQ=MONTHLY;BYMONTHDAY=5"), "2026-01-01", "2026-12-30")
        assertTrue(occ.size >= 11)
        assertEquals(setOf("08:30"), clock(occ, "Europe/London").toSet())
    }

    @Test
    fun leavesAZoneWithoutSummerTimeAsItWas() {
        val occ = expand(weekly("2026-09-01T09:00", "Asia/Ho_Chi_Minh", "FREQ=WEEKLY;BYDAY=TU"), "2026-09-01", "2026-11-30")
        assertEquals(setOf("09:00"), clock(occ, "Asia/Ho_Chi_Minh").toSet())
    }

    @Test
    fun aFloatingSeriesRepeatsAtItsClockTimeWhereThePhoneIs() {
        val sydney = ZoneId.of("Australia/Sydney")
        val text = ics(vevent("UID:f", "DTSTART:20260901T090000", "DTEND:20260901T093000", "RRULE:FREQ=WEEKLY;BYDAY=TU"))
        assertEquals(setOf("09:00"), clock(expand(text, "2026-09-01", "2026-10-31", sydney), "Australia/Sydney").toSet())
    }

    @Test
    fun eachDateIsHalfAnHourLongInRealTime() {
        for (o in expand(weekly("2026-09-01T09:00", "Australia/Sydney", "FREQ=WEEKLY;BYDAY=TU"), "2026-09-01", "2026-10-31")) {
            assertEquals(30L, java.time.Duration.between(o.start, o.end).toMinutes())
        }
    }

    // ---- recurrence-until-and-zones.test.ts ----

    private fun shown(text: String, zone: String, from: String, to: String) =
        expand(text, from, to).map { it.start.atZone(ZoneId.of(zone)).toLocalDateTime().toString().replace('T', ' ') }

    @Test
    fun westOfUtcAnUntilStopsBeforeTheCutDay() {
        val text = weekly("2026-07-06T09:00", "America/New_York", "FREQ=DAILY;UNTIL=20260710T125959Z")
        assertEquals((6..9).map { "2026-07-0$it 09:00" }, shown(text, "America/New_York", "2026-07-01", "2026-07-19"))
    }

    @Test
    fun eastOfUtcADateExactlyAtUntilStays() {
        val days = shown(weekly("2026-07-06T09:00", "Asia/Ho_Chi_Minh", "FREQ=DAILY;UNTIL=20260710T020000Z"), "Asia/Ho_Chi_Minh", "2026-07-01", "2026-07-19")
        assertEquals(5, days.size)
        assertEquals("2026-07-10 09:00", days.last())
    }

    @Test
    fun anUntilHoldsAcrossAChangeOfOffset() {
        val text = weekly("2026-09-29T09:00", "Australia/Sydney", "FREQ=WEEKLY;UNTIL=20261012T215959Z")
        assertEquals(listOf("2026-09-29 09:00", "2026-10-06 09:00"), shown(text, "Australia/Sydney", "2026-09-01", "2026-10-31"))
    }

    @Test
    fun anUntilInUtcIsAsItWas() {
        val text = ics(vevent("UID:u", "DTSTART:20260706T090000Z", "DTEND:20260706T093000Z", "RRULE:FREQ=DAILY;UNTIL=20260709T090000Z"))
        assertEquals(4, expand(text, "2026-07-01", "2026-07-19").size)
    }

    @Test
    fun aBerlinMeetingNamedTheWindowsWayKeeps0900Berlin() {
        val text = weekly("2026-10-13T09:00", "W. Europe Standard Time", "FREQ=WEEKLY;BYDAY=TU")
        val occ = expand(text, "2026-10-01", "2026-11-10", ZoneId.of("Asia/Ho_Chi_Minh"))
        assertEquals(
            listOf("2026-10-13T09:00", "2026-10-20T09:00", "2026-10-27T09:00", "2026-11-03T09:00", "2026-11-10T09:00"),
            occ.map { it.start.atZone(ZoneId.of("Europe/Berlin")).toLocalDateTime().toString() },
        )
    }

    @Test
    fun aMultiDayEventCoversEachOfItsDays() {
        val trip = ics(vevent("UID:trip", "SUMMARY:Trip", "DTSTART;VALUE=DATE:20260914", "DTEND;VALUE=DATE:20260918"))
        val occ = expand(trip, "2026-09-01", "2026-09-30").single()
        assertEquals(LocalDate.parse("2026-09-14") to LocalDate.parse("2026-09-17"), occ.firstDay to occ.lastDay)
        val night = ics(vevent("UID:n", "DTSTART;TZID=Asia/Ho_Chi_Minh:20260914T220000", "DTEND;TZID=Asia/Ho_Chi_Minh:20260915T000000"))
        val o = expand(night, "2026-09-14", "2026-09-15", ZoneId.of("Asia/Ho_Chi_Minh")).single()
        assertEquals(o.firstDay, o.lastDay)
    }

    @Test
    fun theNearestDateOfASeriesIsTheNextOneToCome() {
        val res = ParsedResource.parse(ics(vevent(*standup, "RRULE:FREQ=WEEKLY;BYDAY=MO")), ZoneOffset.UTC)
        val near = Occurrences.nearest(res, "r1", "c1", Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC)
        assertEquals("2026-11-30T09:00:00Z", near!!.start.toString())
    }

    @Test
    fun aSeriesIsFoundAtItsNextDateAndItsLastOne() {
        val text = ics(vevent("UID:s", "SUMMARY:Standup", "DTSTART:20261102T020000Z", "DTEND:20261102T023000Z", "RRULE:FREQ=WEEKLY;COUNT=6"))
        val res = ParsedResource.parse(text, ZoneOffset.UTC)
        val now = java.time.Instant.parse("2026-11-24T00:00:00Z")
        assertEquals(LocalDate.parse("2026-11-30"), Occurrences.next(res, "r", "c", now, ZoneOffset.UTC)!!.firstDay)
        assertEquals(LocalDate.parse("2026-11-23"), Occurrences.last(res, "r", "c", now, ZoneOffset.UTC)!!.firstDay)
        // After its last date it has no next one; before its first, no last one.
        assertNull(Occurrences.next(res, "r", "c", java.time.Instant.parse("2026-12-08T00:00:00Z"), ZoneOffset.UTC))
        assertNull(Occurrences.last(res, "r", "c", java.time.Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC))
    }
}
