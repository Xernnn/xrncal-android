package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.ICalTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.Freq
import org.dmfs.rfc5545.recur.RecurrenceRule

/**
 * Recurrence expansion with a ceiling on what one rule may cost (the laptop app's
 * bounded-rrule.ts, here over lib-recur). A rule is input like any other: it comes from other
 * programs, invitations and imported files. So a series that repeats more often than daily is
 * not expanded (its first date alone shows); one with more than 24 times a day, or a rule longer
 * than any real one, neither; a walk is metered per series and per calendar; and one calendar's
 * series produce at most [CALENDAR_MAX_OCCURRENCES] dates per query.
 *
 * Expansion runs on wall-clock times: a weekly 09:00 is 09:00 every week where it lives, whatever
 * the zone's offset does in between. The caller turns each wall-clock start into an instant.
 * lib-recur counts DTSTART as the first date even when the rule wouldn't produce it (RFC 5545 3.8.5.3).
 */
object Recurrence {
    const val MAX_RULE_LENGTH = 512
    const val MAX_TIMES_PER_DAY = 24
    const val SERIES_WORK_FLOOR = 5_000L
    const val SERIES_WORK_PER_DAY = 64L
    const val MAX_WALK_DAYS = 40L * 366
    const val CALENDAR_WORK_PER_DAY = 20_000L
    const val CALENDAR_WORK_FLOOR = 5_000_000L
    const val CALENDAR_MAX_OCCURRENCES = 50_000

    /** Work left for one calendar's series in one query. */
    class Budget(var remaining: Long, var occurrencesLeft: Int = CALENDAR_MAX_OCCURRENCES) {
        /** Set once a series had more dates than were left. */
        var capped = false
    }

    fun budget(from: LocalDate, to: LocalDate, maxOccurrences: Int = CALENDAR_MAX_OCCURRENCES): Budget {
        val days = maxOf(0L, ChronoUnit.DAYS.between(from, to))
        return Budget(maxOf(CALENDAR_WORK_FLOOR, CALENDAR_WORK_PER_DAY * days), maxOccurrences)
    }

    /** A rule this won't expand; the series' first date shows alone, as for a rule that can't be read. */
    class Unexpandable(reason: String) : Exception("Recurrence not expanded: $reason")

    /** The rule as lib-recur reads it, or [Unexpandable]. */
    fun parse(rule: String): RecurrenceRule {
        val text = rule.trim().removePrefix("RRULE:")
        if (text.length > MAX_RULE_LENGTH) throw Unexpandable("rule too long")
        val parsed = try {
            RecurrenceRule(text)
        } catch (e: Exception) {
            throw Unexpandable("unreadable: ${e.message}")
        }
        if (parsed.freq in setOf(Freq.SECONDLY, Freq.MINUTELY, Freq.HOURLY)) throw Unexpandable("repeats more often than daily")
        val times = listOf(RecurrenceRule.Part.BYHOUR, RecurrenceRule.Part.BYMINUTE, RecurrenceRule.Part.BYSECOND)
            .fold(1) { n, part -> n * (parsed.getByPart(part)?.size?.takeIf { it > 0 } ?: 1) }
        if (times > MAX_TIMES_PER_DAY) throw Unexpandable("too many times a day")
        return parsed
    }

    /**
     * The wall-clock starts of [rule] (an RRULE value) from [start] that fall from [from] to [to],
     * both inclusive. [untilToWall] turns a UTC UNTIL into this series' wall clock. A walk that runs
     * out of work returns what it found: for a rule with no more dates in reach, that is all of them.
     */
    fun between(
        rule: String,
        start: LocalDateTime,
        allDay: Boolean,
        from: LocalDateTime,
        to: LocalDateTime,
        budget: Budget? = null,
        untilToWall: (LocalDateTime) -> LocalDateTime = { it },
    ): List<LocalDateTime> {
        val parsed = parse(wallClockUntil(rule.trim().removePrefix("RRULE:"), allDay, untilToWall))
        val first = if (allDay) DateTime(start.year, start.monthValue - 1, start.dayOfMonth)
        else DateTime(start.year, start.monthValue - 1, start.dayOfMonth, start.hour, start.minute, start.second)
        val iterator = try {
            parsed.iterator(first)
        } catch (e: IllegalArgumentException) {
            // lib-recur looks ahead as it starts, and gives up on a rule that matches nothing for
            // thousands of periods (February 30th): the series is its first date alone.
            return if (!start.isBefore(from) && !start.isAfter(to)) listOf(start) else emptyList()
        } catch (e: Exception) {
            throw Unexpandable("no iterator: ${e.message}")
        }
        val walkDays = maxOf(0L, ChronoUnit.DAYS.between(start.toLocalDate(), to.toLocalDate()))
        val allowance = minOf(SERIES_WORK_FLOOR + SERIES_WORK_PER_DAY * minOf(walkDays, MAX_WALK_DAYS), budget?.remaining ?: Long.MAX_VALUE)
        var work = 0L
        val out = mutableListOf<LocalDateTime>()
        try {
            // A COUNT runs from the first date, so only a series without one can start its walk late.
            if (parsed.count == null && from.isAfter(start)) {
                iterator.fastForward(if (allDay) DateTime(from.year, from.monthValue - 1, from.dayOfMonth) else DateTime(from.year, from.monthValue - 1, from.dayOfMonth, from.hour, from.minute, from.second))
                work += 8
            }
            while (iterator.hasNext()) {
                if (++work > allowance) break
                val d = iterator.nextDateTime()
                val local = if (d.isAllDay) LocalDateTime.of(d.year, d.month + 1, d.dayOfMonth, 0, 0)
                else LocalDateTime.of(d.year, d.month + 1, d.dayOfMonth, d.hours, d.minutes, d.seconds)
                if (local.isAfter(to)) break
                if (local.isBefore(from)) continue
                if (budget != null) {
                    if (budget.occurrencesLeft <= 0) {
                        budget.capped = true
                        break
                    }
                    budget.occurrencesLeft--
                }
                out += local
            }
        } catch (e: IllegalArgumentException) {
            // lib-recur gives up on a rule that matches nothing for thousands of periods (February
            // 30th): what was found so far is all there is.
        } finally {
            if (budget != null) budget.remaining = maxOf(0L, budget.remaining - work)
        }
        return out
    }

    /**
     * UNTIL in the same space as the walk. With a zoned start RFC 5545 makes it UTC, a real instant;
     * the walk is in wall clock, so it is moved there. An all-day series takes its date; a timed one
     * whose UNTIL is a bare date runs to the end of that day.
     */
    internal fun wallClockUntil(rule: String, allDay: Boolean, untilToWall: (LocalDateTime) -> LocalDateTime): String =
        rule.replace(Regex("""UNTIL=(\d{8})(T(\d{6})(Z?))?""", RegexOption.IGNORE_CASE)) { m ->
            val date = m.groupValues[1]
            val clock = m.groupValues[3]
            val utc = m.groupValues[4].isNotEmpty()
            val parsed = runCatching {
                val d = LocalDate.parse(date, ICalTime.DATE)
                if (clock.isEmpty()) LocalDateTime.of(d, LocalTime.MAX.withNano(0)) else LocalDateTime.parse(date + "T" + clock, ICalTime.DATE_TIME)
            }.getOrNull() ?: return@replace m.value
            val wall = if (utc) untilToWall(parsed) else parsed
            if (allDay) "UNTIL=" + (if (utc) parsed else wall).toLocalDate().format(ICalTime.DATE)
            else "UNTIL=" + wall.format(ICalTime.DATE_TIME)
        }
}
