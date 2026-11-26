package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the agenda and month widgets show. */
class WidgetDataTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-26") // a Thursday, lunar 18/10
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val h24: (LocalTime) -> String = { "%02d:%02d".format(it.hour, it.minute) }

    private fun timed(title: String, day: String, from: String, to: String, cal: String = "work", place: String = "", toDay: String = day) =
        occurrence(Instant.parse("${day}T$from:00Z"), Instant.parse("${toDay}T$to:00Z"), LocalDate.parse(day), title, lastDay = LocalDate.parse(toDay))
            .copy(calendarId = cal, resourceId = title, location = place)

    private fun allDay(title: String, first: String, last: String = first, cal: String = "home") =
        occurrence(LocalDate.parse(first).atStartOfDay(utc).toInstant(), LocalDate.parse(last).plusDays(1).atStartOfDay(utc).toInstant(), LocalDate.parse(first), title, allDay = true, lastDay = LocalDate.parse(last)).copy(calendarId = cal, resourceId = title)

    private fun agenda(list: List<Occurrence>, at: Instant = now, hidden: Set<String> = emptySet(), maxRows: Int = WidgetData.AGENDA_ROWS) =
        WidgetData.agenda(list, at.atZone(utc).toLocalDate(), at, utc, hidden, h24, maxRows = maxRows)

    /** The days as `2026-11-26: Gym, Pay rent`. */
    private fun days(view: WidgetData.AgendaView) = view.days.map { d -> "${d.date}: " + d.chips.joinToString { it.title } }

    private fun soon(o: Occurrence, at: Instant) = WidgetData.countdown(o, at, at.atZone(utc).toLocalDate(), utc)

    @Test
    fun theCardTakesTheEarliestTimedEventNotOverAndTheDaysLeaveItOut() {
        val list = listOf(
            allDay("Pay rent", "2026-11-26"),
            timed("Team standup", "2026-11-26", "09:00", "09:30"), // over at noon
            timed("Gym", "2026-11-26", "17:30", "18:30"),
            timed("Design review", "2026-11-26", "14:00", "15:00", place = "Room 4"),
            timed("Dentist", "2026-11-27", "10:00", "11:00"),
        )
        val view = agenda(list)
        val next = view.next!!
        assertEquals("Design review", next.title)
        assertEquals("14:00 – 15:00 · Room 4", next.time)
        assertEquals("in 2 h", next.countdown)
        assertEquals(listOf("2026-11-26: Pay rent, Gym", "2026-11-27: Dentist"), days(view))
    }

    @Test
    fun anEventOnNowHoldsTheCardUntilItEnds() {
        val list = listOf(timed("Lunch with Sam", "2026-11-26", "11:30", "12:30"), timed("Design review", "2026-11-26", "12:15", "13:00"))
        val view = agenda(list)
        assertEquals("Lunch with Sam", view.next!!.title)
        assertEquals("now", view.next!!.countdown)
        assertEquals(listOf("2026-11-26: Design review"), days(view))
        // The review, which ends after the lunch, takes the card when the lunch ends.
        assertEquals("Design review", agenda(list, Instant.parse("2026-11-26T12:30:00Z")).next!!.title)
    }

    @Test
    fun aDayLeftWithOnlyTheCardsEventIsLeftOut() {
        val list = listOf(timed("Movie night", "2026-11-28", "19:00", "22:00"), allDay("Pay rent", "2026-11-29"))
        val view = agenda(list)
        assertEquals("Movie night", view.next!!.title)
        assertEquals("in 2 d", view.next!!.countdown)
        assertEquals(listOf("2026-11-29: Pay rent"), days(view))
    }

    @Test
    fun allDayEventsAndHiddenCalendarsNeverTakeTheCard() {
        val list = listOf(allDay("Trip", "2026-11-26", "2026-11-28"), timed("Private", "2026-11-26", "13:00", "14:00", cal = "hidden"))
        val view = agenda(list, hidden = setOf("hidden"))
        assertNull(view.next)
        assertEquals(listOf("2026-11-26: Trip", "2026-11-27: Trip", "2026-11-28: Trip"), days(view))
        assertNull(agenda(emptyList()).next)
    }

    @Test
    fun theAgendaLeavesOutWhatIsOverAndWhatIsPastItsDays() {
        val list = listOf(
            timed("Team standup", "2026-11-26", "09:00", "09:15"),
            allDay("Trip", "2026-11-27", "2026-11-28"),
            timed("Far off", "2026-12-20", "09:00", "10:00"),
        )
        val view = agenda(list)
        assertNull(view.next)
        assertEquals(listOf("2026-11-27: Trip", "2026-11-28: Trip"), days(view))
    }

    @Test
    fun theAgendaStopsAtItsRowsCountingEachDayAndEachEvent() {
        val list = listOf(timed("First", "2026-11-26", "13:00", "13:30")) +
            (0 until 10).map { timed("E$it", "2026-11-27", "0$it:00", "0$it:30") } +
            timed("Later", "2026-11-28", "09:00", "10:00")
        val view = agenda(list, maxRows = 5)
        assertEquals("First", view.next!!.title)
        assertEquals(listOf("2026-11-27: E0, E1, E2, E3"), days(view))
    }

    @Test
    fun aChipCarriesItsTimeAndPlace() {
        fun chip(o: Occurrence, day: String = o.firstDay.toString(), time: (LocalTime) -> String = h24) = WidgetData.chipTime(o, LocalDate.parse(day), utc, time)
        assertEquals("09:00 – 09:30 · Room 4", chip(timed("Team standup", "2026-11-26", "09:00", "09:30", place = "Room 4")))
        assertEquals("All day", chip(allDay("Pay rent", "2026-11-27")))
        assertEquals("23:59", chip(timed("Assignment due", "2026-12-09", "23:59", "23:59")))
        assertEquals("09:00 – 09:30 · 1 Example St, Springfield", chip(timed("Visit", "2026-11-26", "09:00", "09:30", place = " 1 Example St\nSpringfield ")))
        val night = timed("Night shift", "2026-11-26", "22:00", "02:00", toDay = "2026-11-28")
        assertEquals("22:00 – Sat 02:00", chip(night))
        assertEquals("All day", chip(night, "2026-11-27"))
        assertEquals("until 02:00", chip(night, "2026-11-28"))
        // The 12-hour clock, as the app writes it.
        val h12: (LocalTime) -> String = { "%d:%02d%s".format(if (it.hour % 12 == 0) 12 else it.hour % 12, it.minute, if (it.hour < 12) "a" else "p") }
        assertEquals("9:00a – 9:30a", chip(timed("Team standup", "2026-11-26", "09:00", "09:30"), time = h12))
    }

    @Test
    fun theCardWritesTheWeekdaysOfAnEventOverMidnight() {
        assertEquals("Thu 22:00 – Fri 02:00 · Hall", WidgetData.cardTime(timed("Party", "2026-11-26", "22:00", "02:00", place = "Hall", toDay = "2026-11-27"), utc, h24))
        assertEquals("23:59", WidgetData.cardTime(timed("Assignment due", "2026-12-09", "23:59", "23:59"), utc, h24))
    }

    @Test
    fun theCountdownIsShortAndRoundedUpWithinTheHour() {
        val o = timed("Team standup", "2026-11-26", "09:00", "09:30")
        fun at(t: String) = soon(o, Instant.parse("2026-11-26T$t"))
        assertEquals("in 25 min", at("08:35:00Z"))
        assertEquals("in 25 min", at("08:35:30Z"))
        assertEquals("in 1 min", at("08:59:01Z"))
        assertEquals("now", at("09:00:00Z"))
        assertEquals("now", at("09:29:59Z"))
        assertEquals("in 59 min", at("08:01:00Z"))
        assertEquals("in 1 h", at("08:00:00Z"))
        assertEquals("in 1 h", at("07:01:00Z"))
        assertEquals("in 2 h", at("07:00:00Z"))
        assertEquals("in 23 h", soon(o, Instant.parse("2026-11-25T09:01:00Z")))
        assertEquals("in 1 d", soon(o, Instant.parse("2026-11-25T08:00:00Z")))
        assertEquals("in 3 d", soon(o, Instant.parse("2026-11-23T20:00:00Z")))
    }

    @Test
    fun theLunarDateMarksTheNewAndTheFullMoon() {
        assertEquals(WidgetData.LunarText("18/10", null), WidgetData.lunar(today))
        assertEquals(WidgetData.LunarText("1/11", Moon.NEW), WidgetData.lunar(LocalDate.parse("2026-12-09")))
        assertEquals(WidgetData.LunarText("15", Moon.FULL), WidgetData.lunar(LocalDate.parse("2026-11-23")))
        assertEquals(WidgetData.LunarText("Tết", Moon.NEW), WidgetData.lunar(LocalDate.parse("2027-02-06")))
    }

    @Test
    fun aDayKnowsItsWeekdayAndWhetherItIsAWeekend() {
        assertEquals("THU", WidgetData.ChipDay(today, emptyList()).weekday)
        assertFalse(WidgetData.ChipDay(today, emptyList()).weekend)
        assertTrue(WidgetData.ChipDay(LocalDate.parse("2026-11-29"), emptyList()).weekend)
    }

    @Test
    fun theMonthWidgetHasTheMonthsWeeksWithItsBars() {
        val list = listOf(allDay("Trip", "2026-11-28", "2026-11-30"), timed("Dentist", "2026-11-26", "16:00", "17:00", cal = "hidden"))
        val weeks = WidgetData.month(list, YearMonth.of(2026, 11), DayOfWeek.MONDAY, hidden = setOf("hidden"), lanes = 2)
        assertEquals(6, weeks.size)
        assertEquals(listOf("Trip", "Trip"), weeks.flatMap { w -> w.bars.map { it.occurrence.title } })
        assertTrue(weeks.all { w -> w.bars.none { it.occurrence.title == "Dentist" } })
    }
}
