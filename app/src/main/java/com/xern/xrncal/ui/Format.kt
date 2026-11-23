package com.xern.xrncal.ui

import android.content.Context
import com.xern.xrncal.data.Clock
import com.xern.xrncal.domain.Lunar
import com.xern.xrncal.domain.Occurrence
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Dates and times as the screens write them: short, in English, the clock as Settings say. */
object Format {
    private val L = Locale.ENGLISH

    fun is24(context: Context, clock: Clock): Boolean = when (clock) {
        Clock.H24 -> true
        Clock.H12 -> false
        Clock.SYSTEM -> android.text.format.DateFormat.is24HourFormat(context)
    }

    /** `16:00`, or One Calendar's compact `4:00p`. */
    fun time(t: LocalTime, h24: Boolean): String =
        if (h24) "%02d:%02d".format(t.hour, t.minute)
        else "%d:%02d%s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "a" else "p")

    /** An hour label on the grid: `09`, or `9a`. */
    fun hour(h: Int, h24: Boolean): String = if (h24) "%02d".format(h) else "%d%s".format(if (h % 12 == 0) 12 else h % 12, if (h < 12) "a" else "p")

    /** `16:00 – 17:00`; across days, each end with its day. */
    fun span(o: Occurrence, h24: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        if (o.allDay) return if (o.multiDay) "${dayShort(o.firstDay)} – ${dayShort(o.lastDay)}" else "All day"
        val s = o.start.atZone(zone)
        val e = o.end.atZone(zone)
        return if (o.multiDay) "${dayShort(s.toLocalDate())} ${time(s.toLocalTime(), h24)} – ${dayShort(e.toLocalDate())} ${time(e.toLocalTime(), h24)}"
        else "${time(s.toLocalTime(), h24)} – ${time(e.toLocalTime(), h24)}"
    }

    /** `Mon 24 Nov`. */
    fun dayShort(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, L)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, L)}"

    /** `Monday 24 November 2026`. */
    fun dayLong(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, L)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, L)} ${d.year}"

    /** `Mon, 24 Nov 2026`, for the editor's date buttons. */
    fun dateButton(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, L)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, L)} ${d.year}"

    /** The top bar's period name: `September`, with the year when it isn't this year's. */
    fun month(ym: YearMonth, today: LocalDate): String {
        val name = ym.month.getDisplayName(TextStyle.FULL, L)
        return if (ym.year == today.year) name else "$name ${ym.year}"
    }

    /** `24 FRIDAY`, the list's day header. */
    fun listDay(d: LocalDate): String = "${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.FULL, L).uppercase(L)}"

    fun monthUpper(d: LocalDate, today: LocalDate): String =
        d.month.getDisplayName(TextStyle.FULL, L).uppercase(L) + if (d.year != today.year) " ${d.year}" else ""

    fun weekdayShort(d: java.time.DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, L)

    fun weekdayLetter(d: java.time.DayOfWeek): String = d.getDisplayName(TextStyle.NARROW, L)

    /** The lunar date a header or the detail carries: `Âm 6/7`. */
    fun lunar(d: LocalDate): String = Lunar.ofSolar(d).let { "${it.day}/${it.month}${if (it.leap) "*" else ""}" }

    fun lunarLong(d: LocalDate): String = Lunar.longLabel(Lunar.ofSolar(d))
}
