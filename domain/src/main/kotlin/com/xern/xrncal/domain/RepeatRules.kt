package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.ICalTime
import com.xern.xrncal.domain.ical.Zones
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** How a series ends: never, after [count] dates, or on [until] (that day included). */
data class RepeatEnd(val count: Int? = null, val until: LocalDate? = null)

/**
 * A repeat the editor can show and change: every [interval] days, weeks (on [weekdays]), months
 * (on the date, or on the nth weekday with [monthlyByWeekday]) or years. A rule from another
 * program that says more than this is kept as it is and shown as "Custom".
 */
data class Repeat(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyByWeekday: Boolean = false,
    val end: RepeatEnd = RepeatEnd(),
)

object RepeatRules {
    private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private fun code(d: DayOfWeek) = DAY_CODES[d.value - 1]

    /** The preset choices, by the date the event starts on. */
    enum class Preset { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY, CUSTOM }

    fun preset(rule: String?, start: LocalDate): Preset {
        if (rule == null) return Preset.NONE
        val r = parse(rule, start) ?: return Preset.CUSTOM
        if (r.interval != 1 || r.end != RepeatEnd()) return Preset.CUSTOM
        return when (r.frequency) {
            Frequency.DAILY -> Preset.DAILY
            Frequency.WEEKLY -> when (r.weekdays) {
                setOf(start.dayOfWeek) -> Preset.WEEKLY
                WEEKDAYS -> Preset.WEEKDAYS
                else -> Preset.CUSTOM
            }
            Frequency.MONTHLY -> if (r.monthlyByWeekday) Preset.CUSTOM else Preset.MONTHLY
            Frequency.YEARLY -> Preset.YEARLY
        }
    }

    val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    fun ofPreset(p: Preset, start: LocalDate): Repeat? = when (p) {
        Preset.NONE, Preset.CUSTOM -> null
        Preset.DAILY -> Repeat(Frequency.DAILY)
        Preset.WEEKDAYS -> Repeat(Frequency.WEEKLY, weekdays = WEEKDAYS)
        Preset.WEEKLY -> Repeat(Frequency.WEEKLY, weekdays = setOf(start.dayOfWeek))
        Preset.MONTHLY -> Repeat(Frequency.MONTHLY)
        Preset.YEARLY -> Repeat(Frequency.YEARLY)
    }

    /** The RRULE for [r], for a series starting on [start]. */
    fun rule(r: Repeat, start: LocalDate, allDay: Boolean, zone: ZoneId): String = buildString {
        append("FREQ=").append(r.frequency.name)
        if (r.interval > 1) append(";INTERVAL=").append(r.interval)
        when (r.frequency) {
            Frequency.WEEKLY -> {
                val days = r.weekdays.ifEmpty { setOf(start.dayOfWeek) }
                if (days != setOf(start.dayOfWeek)) append(";BYDAY=").append(DayOfWeek.entries.filter { it in days }.joinToString(",") { code(it) })
            }
            Frequency.MONTHLY -> if (r.monthlyByWeekday) {
                val nth = (start.dayOfMonth - 1) / 7 + 1
                append(";BYDAY=").append(if (nth == 5) "-1" else "$nth").append(code(start.dayOfWeek))
            }
            else -> Unit
        }
        r.end.count?.let { append(";COUNT=").append(maxOf(1, it)) }
        if (r.end.count == null) r.end.until?.let { append(";UNTIL=").append(until(it, allDay, zone)) }
    }

    /**
     * UNTIL for a series that ends on [day], that day included: the date for an all-day series, and
     * for a timed one the end of that day in its zone, as UTC (RFC 5545 3.3.10).
     */
    fun until(day: LocalDate, allDay: Boolean, zone: ZoneId): String =
        if (allDay) day.format(ICalTime.DATE)
        else Zones.instantOf(LocalDateTime.of(day, LocalTime.of(23, 59, 59)), zone.rules).atOffset(ZoneOffset.UTC).toLocalDateTime().format(ICalTime.DATE_TIME) + "Z"

    /** A rule's UNTIL in the form its series needs (a date, or UTC), the day it names kept. */
    fun fitUntil(rule: String, allDay: Boolean, zone: ZoneId): String {
        val m = Regex("""UNTIL=(\d{8})(T(\d{6})(Z?))?""", RegexOption.IGNORE_CASE).find(rule) ?: return rule
        val utc = m.groupValues[4].isNotEmpty()
        if (allDay && m.groupValues[2].isEmpty()) return rule
        if (!allDay && utc) return rule
        val day = LocalDate.parse(m.groupValues[1], ICalTime.DATE)
        return rule.replace(m.value, "UNTIL=" + until(day, allDay, zone))
    }

    /** What the editor can show of [rule], or null when it says more (BYSETPOS, several months, ...). */
    fun parse(rule: String, start: LocalDate): Repeat? {
        val parts = rule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
            it.substringBefore('=').uppercase() to it.substringAfter('=', "")
        }
        val freq = when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> Frequency.DAILY
            "WEEKLY" -> Frequency.WEEKLY
            "MONTHLY" -> Frequency.MONTHLY
            "YEARLY" -> Frequency.YEARLY
            else -> return null
        }
        val known = setOf("FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL", "WKST", "BYMONTHDAY", "BYMONTH")
        if (parts.keys.any { it !in known }) return null
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it >= 1 } ?: if (parts.containsKey("INTERVAL")) return null else 1
        val until = parts["UNTIL"]?.let { u -> runCatching { LocalDate.parse(u.take(8), ICalTime.DATE) }.getOrNull() ?: return null }
        val count = parts["COUNT"]?.let { it.toIntOrNull() ?: return null }
        val end = RepeatEnd(count, until)
        val byDay = parts["BYDAY"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
        val byMonthDay = parts["BYMONTHDAY"]
        val byMonth = parts["BYMONTH"]
        return when (freq) {
            Frequency.DAILY -> if (byDay != null || byMonthDay != null || byMonth != null) null else Repeat(freq, interval, end = end)
            Frequency.WEEKLY -> {
                if (byMonthDay != null || byMonth != null) return null
                val days = byDay?.map { d -> DAY_CODES.indexOf(d).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) } ?: return null }?.toSet() ?: setOf(start.dayOfWeek)
                Repeat(freq, interval, days, end = end)
            }
            Frequency.MONTHLY -> {
                if (byMonth != null) return null
                when {
                    byDay != null -> {
                        if (byMonthDay != null || byDay.size != 1) return null
                        val m = Regex("^([+-]?\\d)(MO|TU|WE|TH|FR|SA|SU)$").matchEntire(byDay[0]) ?: return null
                        val nth = m.groupValues[1].toInt()
                        if (code(start.dayOfWeek) != m.groupValues[2]) return null
                        if (nth != (start.dayOfMonth - 1) / 7 + 1 && nth != -1) return null
                        Repeat(freq, interval, monthlyByWeekday = true, end = end)
                    }
                    byMonthDay != null -> if (byMonthDay.toIntOrNull() == start.dayOfMonth) Repeat(freq, interval, end = end) else null
                    else -> Repeat(freq, interval, end = end)
                }
            }
            Frequency.YEARLY -> {
                if (byDay != null) return null
                if (byMonth != null && byMonth.toIntOrNull() != start.monthValue) return null
                if (byMonthDay != null && byMonthDay.toIntOrNull() != start.dayOfMonth) return null
                Repeat(freq, interval, end = end)
            }
        }
    }

    /** A short description: "Every week on Tue", "Every 2 months", "Every day, 5 times", "Every year, until 31 Dec 2027". */
    fun describe(rule: String, start: LocalDate, locale: Locale = Locale.ENGLISH): String {
        val r = parse(rule, start) ?: return "Custom"
        val unit = when (r.frequency) {
            Frequency.DAILY -> "day"
            Frequency.WEEKLY -> "week"
            Frequency.MONTHLY -> "month"
            Frequency.YEARLY -> "year"
        }
        val every = if (r.interval == 1) "Every $unit" else "Every ${r.interval} ${unit}s"
        val detail = when (r.frequency) {
            Frequency.WEEKLY -> when {
                r.weekdays == WEEKDAYS -> " on weekdays"
                r.weekdays.size == 7 -> " on every day"
                else -> " on " + DayOfWeek.entries.filter { it in r.weekdays }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
            }
            Frequency.MONTHLY -> if (r.monthlyByWeekday) {
                val nth = (start.dayOfMonth - 1) / 7 + 1
                val ord = listOf("first", "second", "third", "fourth", "last").getOrElse(nth - 1) { "last" }
                " on the $ord ${start.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
            } else {
                " on day ${start.dayOfMonth}"
            }
            Frequency.YEARLY -> " on ${start.dayOfMonth} ${start.month.getDisplayName(TextStyle.SHORT, locale)}"
            Frequency.DAILY -> ""
        }
        val end = when {
            r.end.count != null -> if (r.end.count == 1) ", once" else ", ${r.end.count} times"
            r.end.until != null -> ", until ${r.end.until.dayOfMonth} ${r.end.until.month.getDisplayName(TextStyle.SHORT, locale)} ${r.end.until.year}"
            else -> ""
        }
        return every + detail + end
    }
}
