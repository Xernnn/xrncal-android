package com.xern.xrncal.domain

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/**
 * All-day end dates (the laptop app's all-day.ts). iCalendar's DTEND;VALUE=DATE is exclusive: a
 * one-day event on the 14th ends on the 15th. Everything here past the reader works with the last
 * day the event covers, so the conversion happens at the boundary, both ways.
 */
object AllDay {
    /** Exclusive end -> the last day covered. An end at or before the start is a single day. */
    fun lastDay(start: LocalDate, exclusiveEnd: LocalDate?): LocalDate =
        if (exclusiveEnd == null || !exclusiveEnd.isAfter(start)) start else exclusiveEnd.minusDays(1)

    /** The last day covered -> the exclusive end to write; never at or before the start. */
    fun exclusiveEnd(start: LocalDate, lastDay: LocalDate): LocalDate =
        if (lastDay.isBefore(start)) start.plusDays(1) else lastDay.plusDays(1)

    fun covers(first: LocalDate, last: LocalDate, day: LocalDate): Boolean = !day.isBefore(first) && !day.isAfter(last)

    /** A corrupt or absurd span is never walked for more than this many days. */
    const val MAX_SPAN_DAYS = 366

    /**
     * Every day an occurrence covering [first]..[last] appears on, limited to [shown] when given:
     * walked from its own first day, an event longer than the cap showed only in its first year.
     */
    fun days(first: LocalDate, last: LocalDate, shown: ClosedRange<LocalDate>? = null): List<LocalDate> {
        if (!last.isAfter(first)) return listOf(first)
        var cursor = if (shown != null && shown.start.isAfter(first)) shown.start else first
        val end = if (shown != null && shown.endInclusive.isBefore(last)) shown.endInclusive else last
        val out = mutableListOf<LocalDate>()
        while (!cursor.isAfter(end) && out.size < MAX_SPAN_DAYS) {
            out += cursor
            cursor = cursor.plusDays(1)
        }
        return out
    }
}

/** How long a new timed event is when nothing says otherwise (event-length.ts). */
object EventLength {
    val OPTIONS = listOf(15, 30, 45, 60, 90, 120)
    const val DEFAULT = 60

    /** The stored length, or an hour when it isn't one of the lengths offered. */
    fun minutes(setting: Any?): Int = OPTIONS.firstOrNull { it == setting } ?: DEFAULT

    /** Where a new event starting at [start] ends: late in the evening, after midnight. */
    fun end(start: ZonedDateTime, setting: Any?): ZonedDateTime = start.plusMinutes(minutes(setting).toLong())
}

/**
 * The hour grid is a clock face (clock-minutes.ts): row 17 is 17:00, whatever the day's length. On
 * the days a zone changes offset, elapsed time and clock time part, so everything that maps between
 * a time and a place on the grid goes through these two.
 */
object ClockMinutes {
    const val DAY = 24 * 60

    /** Minutes from the top of [day]'s grid to [t] by its clock: 0 before the day, 1440 after it. */
    fun ofDay(t: ZonedDateTime, day: LocalDate): Int {
        val dayStart = day.atStartOfDay(t.zone)
        if (!t.isAfter(dayStart)) return 0
        if (t.toLocalDate() != day) return DAY
        return Math.round(t.hour * 60 + t.minute + t.second / 60.0 + t.nano / 60_000_000_000.0).toInt()
    }

    /** The time on [day] whose clock reads [minutes] past midnight; 1440 is the next midnight. */
    fun at(day: LocalDate, minutes: Int, zone: java.time.ZoneId): ZonedDateTime {
        if (minutes >= DAY) return day.plusDays(1).atStartOfDay(zone)
        return ZonedDateTime.ofLocal(LocalDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60)), zone, null)
    }
}

/** Text as search compares it (text-fold.ts): no accents, lower case, words only. "ca phe" finds "Cà phê". */
object TextFold {
    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun accents(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").replace('đ', 'd').replace('Đ', 'D')

    fun forMatch(text: String): String = NOT_WORD.replace(accents(text).lowercase(), " ").trim()

    /** Whether every word of [query] begins a word of [folded] (already folded). */
    fun matches(folded: String, query: String): Boolean {
        val words = forMatch(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val padded = " $folded"
        return words.all { padded.contains(" $it") }
    }
}

/** The first day of the week: Monday, Sunday or Saturday, as the laptop app offers (week-start.ts). */
object WeekStart {
    val CHOICES = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)

    fun startOfWeek(date: LocalDate, first: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - first.value + 7) % 7).toLong())

    fun order(first: DayOfWeek): List<DayOfWeek> = (0 until 7).map { first.plus(it.toLong()) }

    /** The ISO week number of a row of seven days from [rowStart]: named by its Thursday, in every row. */
    fun isoWeekOfRow(rowStart: LocalDate): Int =
        (0L until 7L).map { rowStart.plusDays(it) }.first { it.dayOfWeek == DayOfWeek.THURSDAY }.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
