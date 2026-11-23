package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the home-screen widgets show (docs/spec.md, phase 2 item 12), worked out from the
 * occurrences the views read, without the calendars Widget options leave off.
 */
object WidgetData {
    /** How far ahead the agenda widget looks, as One Calendar's. */
    const val AGENDA_DAYS = 14L

    /** The most rows the agenda widget holds (headers and events), so a busy fortnight stays light. */
    const val AGENDA_ROWS = 80

    /** One row of the agenda widget: a day's header, or one of its events. */
    sealed interface AgendaRow {
        data class Day(val date: LocalDate) : AgendaRow
        data class Event(val occurrence: Occurrence, val date: LocalDate) : AgendaRow
    }

    /**
     * The agenda: from [today], each day with events under its header, all-day ones first, an event
     * over several days under each of them; a timed event already over today is left out, as One
     * Calendar's agenda drops what has passed. At most [maxRows] rows.
     */
    fun agenda(
        occurrences: List<Occurrence>,
        today: LocalDate,
        now: java.time.Instant,
        hidden: Set<String>,
        days: Long = AGENDA_DAYS,
        maxRows: Int = AGENDA_ROWS,
    ): List<AgendaRow> {
        val shown = occurrences.filter { it.calendarId !in hidden && (it.allDay || it.end.isAfter(now)) }
        val out = mutableListOf<AgendaRow>()
        for (d in Agenda.group(shown, today, today.plusDays(days - 1))) {
            if (out.size + 2 > maxRows) break
            out += AgendaRow.Day(d.date)
            for (o in d.items) {
                if (out.size >= maxRows) break
                out += AgendaRow.Event(o, d.date)
            }
        }
        return out
    }

    /** A month widget's week row: the days, and the bars on its [lanes] lines (see [MonthLayout.week]). */
    fun month(occurrences: List<Occurrence>, month: YearMonth, firstDay: DayOfWeek, hidden: Set<String>, lanes: Int): List<MonthLayout.Week> {
        val shown = occurrences.filter { it.calendarId !in hidden }
        return MonthLayout.weeks(month, firstDay, rows = null).map { MonthLayout.week(it, shown, lanes) }
    }
}
