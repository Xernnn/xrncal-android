package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the home-screen widgets show (docs/spec.md, phase 2 item 12), worked out from the
 * occurrences the views read, without the calendars Widget options leave off.
 */
object WidgetData {
    /** How far ahead the agenda widget looks, as One Calendar's. */
    const val AGENDA_DAYS = 14L

    /** The most rows the agenda widget holds (days and events), so a busy fortnight stays light. */
    const val AGENDA_ROWS = 80

    private val L = Locale.ENGLISH

    /** The agenda widget: the days ahead, each day's events as chips beside its date. */
    data class AgendaView(val days: List<ChipDay>)

    /** A day of the list, with its events in the order the day shows them. */
    data class ChipDay(val date: LocalDate, val chips: List<Chip>) {
        /** `THU`, over the date. */
        val weekday: String get() = date.dayOfWeek.getDisplayName(TextStyle.SHORT, L).uppercase(L)
        val weekend: Boolean get() = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    }

    /** An event on one day: its title and its time on that day (`09:00 – 09:30 · Room 4`, `All day`). */
    data class Chip(val occurrence: Occurrence, val title: String, val time: String)

    /** A day's lunar date under its number: `18/10`, or a moon's mark: `1/11` (`Tết`) at the new moon, `15` at the full. */
    data class LunarText(val text: String, val moon: Moon?)

    /**
     * The agenda from [today] for [days] days. Each day lists its events, all-day ones first, an
     * event over several days under each of them; a timed event already over is left out, as One
     * Calendar's agenda drops what has passed, and a day left with none is left out too. At most
     * [maxRows] rows, each day and each event one. [time] writes a time of day as the app does, on
     * the 24 or the 12-hour clock.
     */
    fun agenda(
        occurrences: List<Occurrence>,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        hidden: Set<String>,
        time: (LocalTime) -> String,
        days: Long = AGENDA_DAYS,
        maxRows: Int = AGENDA_ROWS,
    ): AgendaView {
        val last = today.plusDays(days - 1)
        val shown = occurrences.filter { it.calendarId !in hidden && (it.allDay || it.end.isAfter(now)) && !it.firstDay.isAfter(last) }
        val out = mutableListOf<ChipDay>()
        var rows = 0
        for (d in Agenda.group(shown, today, last)) {
            if (rows + 2 > maxRows) break
            rows++
            val chips = mutableListOf<Chip>()
            for (o in d.items) {
                if (rows >= maxRows) break
                chips += Chip(o, title(o), chipTime(o, d.date, zone, time))
                rows++
            }
            out += ChipDay(d.date, chips)
        }
        return AgendaView(out)
    }

    fun title(o: Occurrence): String = o.title.ifBlank { "(no title)" }

    /**
     * An event's time on [day]: `09:00 – 09:30`, the one time when it ends as it starts, `All day`.
     * One over midnight shows its start with the weekday it ends (`22:00 – Fri 02:00`) on its first
     * day, `until 02:00` on its last and `All day` between. Its place follows: `· Room 4`.
     */
    fun chipTime(o: Occurrence, day: LocalDate, zone: ZoneId, time: (LocalTime) -> String): String {
        val s = o.start.atZone(zone).toLocalTime()
        val e = o.end.atZone(zone).toLocalTime()
        val text = when {
            o.allDay -> "All day"
            !o.multiDay -> if (o.start == o.end) time(s) else "${time(s)} – ${time(e)}"
            day == o.firstDay -> "${time(s)} – ${weekday(o.lastDay)} ${time(e)}"
            day == o.lastDay -> "until ${time(e)}"
            else -> "All day"
        }
        return withPlace(text, o.location)
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, L)

    private fun withPlace(text: String, place: String): String {
        val p = place.trim().replace(Regex("""\s*\n\s*"""), ", ")
        return if (p.isEmpty()) text else "$text · $p"
    }

    /**
     * When the agenda must be drawn again to stay right before midnight's redraw: an event on the
     * list ending, which leaves the list then. Null when nothing will.
     */
    fun nextRedraw(view: AgendaView, now: Instant): Instant? =
        view.days.asSequence().flatMap { it.chips }.map { it.occurrence }.filter { !it.allDay && it.end.isAfter(now) }.minOfOrNull { it.end }

    /**
     * When to set an alarm that should go off at [due]. An inexact alarm (AlarmManager.set) may go
     * off as late as three quarters of its wait after the time asked, an hour at most: a minute's
     * wait can come 45 s late, an hour's 45 min. Set at 4/7 of the wait, or an hour before [due]
     * on a wait long enough for the hour to be the limit, its latest is [due] itself; going off
     * early, it is set again for the rest of the way, with nothing drawn. Within 10 s Android
     * doesn't defer it, so [due] as it is.
     */
    fun alarmFor(due: Instant, now: Instant): Instant {
        val wait = Duration.between(now, due).toMillis()
        return when {
            wait <= 10_000 -> due
            wait * 4 / 7 * 3 / 4 >= HOUR -> due.minusMillis(HOUR)
            else -> now.plusMillis(wait * 4 / 7)
        }
    }

    private const val HOUR = 3_600_000L

    /** The lunar date under a day's number. */
    fun lunar(d: LocalDate): LunarText {
        val l = Lunar.ofSolar(d)
        val mark = Lunar.moon(l)
        return when (mark?.moon) {
            null -> LunarText("${l.day}/${l.month}${if (l.leap) "*" else ""}", null)
            Moon.NEW -> LunarText(mark.text, Moon.NEW)
            Moon.FULL -> LunarText("15", Moon.FULL)
        }
    }

    /** A month widget's week row: the days, and the bars on its [lanes] lines (see [MonthLayout.week]). */
    fun month(occurrences: List<Occurrence>, month: YearMonth, firstDay: DayOfWeek, hidden: Set<String>, lanes: Int): List<MonthLayout.Week> {
        val shown = occurrences.filter { it.calendarId !in hidden }
        return MonthLayout.weeks(month, firstDay, rows = null).map { MonthLayout.week(it, shown, lanes) }
    }
}
