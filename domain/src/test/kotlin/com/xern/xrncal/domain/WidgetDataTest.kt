package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the agenda and month widgets show. */
class WidgetDataTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-11-24")
    private val now = Instant.parse("2026-11-24T12:00:00Z")

    private fun timed(title: String, day: String, from: String, to: String, cal: String = "work") =
        occurrence(Instant.parse("${day}T$from:00Z"), Instant.parse("${day}T$to:00Z"), LocalDate.parse(day), title).copy(calendarId = cal, resourceId = title)

    private fun allDay(title: String, first: String, last: String, cal: String = "home") =
        occurrence(LocalDate.parse(first).atStartOfDay(utc).toInstant(), LocalDate.parse(last).plusDays(1).atStartOfDay(utc).toInstant(), LocalDate.parse(first), title, allDay = true, lastDay = LocalDate.parse(last)).copy(calendarId = cal, resourceId = title)

    private fun text(rows: List<WidgetData.AgendaRow>) = rows.map {
        when (it) {
            is WidgetData.AgendaRow.Day -> "# ${it.date}"
            is WidgetData.AgendaRow.Event -> it.occurrence.title
        }
    }

    @Test
    fun theAgendaListsTheDaysAheadWithTheirEventsLeavingOutWhatIsOver() {
        val list = listOf(
            timed("Standup", "2026-11-24", "09:00", "09:15"), // over at noon
            timed("Design review", "2026-11-24", "14:00", "15:00"),
            allDay("Trip", "2026-11-25", "2026-11-26"),
            timed("Dentist", "2026-11-26", "16:00", "17:00"),
            timed("Far off", "2026-12-20", "09:00", "10:00"),
        )
        assertEquals(
            listOf("# 2026-11-24", "Design review", "# 2026-11-25", "Trip", "# 2026-11-26", "Trip", "Dentist"),
            text(WidgetData.agenda(list, today, now, hidden = emptySet())),
        )
    }

    @Test
    fun theAgendaLeavesOffHiddenCalendarsAndStopsAtItsRows() {
        val list = (0 until 10).map { timed("E$it", "2026-11-25", "0$it:00", "0$it:30", cal = if (it % 2 == 0) "work" else "home") }
        assertTrue(text(WidgetData.agenda(list, today, now, hidden = setOf("home"))).none { it.startsWith("E") && it.drop(1).toInt() % 2 == 1 })
        val rows = WidgetData.agenda(list, today, now, hidden = emptySet(), maxRows = 5)
        assertEquals(listOf("# 2026-11-25", "E0", "E1", "E2", "E3"), text(rows))
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
