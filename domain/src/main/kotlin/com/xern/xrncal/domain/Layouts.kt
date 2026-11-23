package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The month grid: which days its rows hold, and where each event's bar goes in a week row. */
object MonthLayout {
    /**
     * The weeks shown for [month], from the week its first day falls in: [rows] of them (six fit
     * every month), or just as many as the month needs when [rows] is null.
     */
    fun weeks(month: YearMonth, first: DayOfWeek, rows: Int? = 6): List<List<LocalDate>> {
        val start = WeekStart.startOfWeek(month.atDay(1), first)
        val needed = ((ChronoUnit.DAYS.between(start, month.atEndOfMonth()) / 7) + 1).toInt()
        val n = rows ?: needed
        return (0 until n).map { w -> (0 until 7).map { d -> start.plusDays((w * 7 + d).toLong()) } }
    }

    /** One bar in a week row: columns [from]..[to] (0-6) on line [lane]. */
    data class Bar(val occurrence: Occurrence, val from: Int, val to: Int, val lane: Int, val continuesBefore: Boolean, val continuesAfter: Boolean)

    /** A week row: its bars, and per day how many events didn't fit ("+N"). */
    data class Week(val days: List<LocalDate>, val bars: List<Bar>, val more: List<Int>)

    /**
     * How many one-line chips fit a month cell [cellHeight] tall under its [header] (the day's
     * number), [lane] tall each with [gap] between: One Calendar fills the cell, about five on the
     * owner's phone. At least one.
     */
    fun lanes(cellHeight: Float, header: Float, lane: Float, gap: Float): Int =
        maxOf(1, ((cellHeight - header + gap) / (lane + gap)).toInt())

    /**
     * Lays out one week row with [lanes] lines a day. Events that span days come first, longest
     * first, then the day's all-day events, then timed ones by start, each on the first line free
     * across its days. What doesn't fit is counted in [Week.more]: One Calendar shows "+N" beside
     * the day's number, so no line is kept back for it unless [keepLineForMore] (a "+N" drawn on
     * the last line instead).
     */
    fun week(days: List<LocalDate>, occurrences: List<Occurrence>, lanes: Int, keepLineForMore: Boolean = false): Week {
        val first = days.first()
        val last = days.last()
        val items = occurrences.filter { !it.firstDay.isAfter(last) && !it.lastDay.isBefore(first) }
            .sortedWith(
                compareBy<Occurrence>({ if (it.multiDay) 0 else if (it.allDay) 1 else 2 })
                    .thenBy { if (it.multiDay) -ChronoUnit.DAYS.between(maxOf(it.firstDay, first), minOf(it.lastDay, last)) else 0 }
                    .thenBy { it.firstDay }
                    .thenBy { it.start }
                    .thenBy { it.title },
            )
        val taken = Array(7) { BooleanArray(items.size + 1) }
        data class Placed(val o: Occurrence, val from: Int, val to: Int, val lane: Int)
        val placed = items.map { o ->
            val from = maxOf(0, ChronoUnit.DAYS.between(first, o.firstDay).toInt())
            val to = minOf(6, ChronoUnit.DAYS.between(first, o.lastDay).toInt())
            var lane = 0
            while ((from..to).any { taken[it][lane] }) lane++
            for (c in from..to) taken[c][lane] = true
            Placed(o, from, to, lane)
        }
        val total = IntArray(7)
        placed.forEach { p -> for (c in p.from..p.to) total[c]++ }
        val limit = IntArray(7) { if (keepLineForMore && total[it] > lanes) maxOf(0, lanes - 1) else lanes }
        val bars = mutableListOf<Bar>()
        val shown = IntArray(7)
        for (p in placed) {
            // A bar shows on the days where its line is within that day's limit, in runs.
            var c = p.from
            while (c <= p.to) {
                if (p.lane >= limit[c]) {
                    c++
                    continue
                }
                val runStart = c
                while (c + 1 <= p.to && p.lane < limit[c + 1]) c++
                bars += Bar(p.o, runStart, c, p.lane, continuesBefore = runStart > p.from || p.o.firstDay.isBefore(days[runStart]), continuesAfter = c < p.to || p.o.lastDay.isAfter(days[c]))
                for (k in runStart..c) shown[k]++
                c++
            }
        }
        return Week(days, bars, (0 until 7).map { total[it] - shown[it] })
    }
}

/** The list view: upcoming events grouped by day, days without any left out. */
object Agenda {
    data class Day(val date: LocalDate, val items: List<Occurrence>)

    /** Each event on every day it covers from [from] to [to]: all-day ones first, then by start. */
    fun group(occurrences: List<Occurrence>, from: LocalDate, to: LocalDate): List<Day> {
        val byDay = sortedMapOf<LocalDate, MutableList<Occurrence>>()
        for (o in occurrences) {
            for (d in AllDay.days(o.firstDay, o.lastDay, from..to)) {
                if (d.isBefore(from) || d.isAfter(to)) continue
                byDay.getOrPut(d) { mutableListOf() } += o
            }
        }
        return byDay.map { (d, list) -> Day(d, sortDay(list, d)) }
    }

    fun sortDay(list: List<Occurrence>, day: LocalDate): List<Occurrence> =
        list.sortedWith(compareBy<Occurrence>({ !(it.allDay || (it.multiDay && it.firstDay.isBefore(day))) }, { it.start }, { it.end }, { it.title }))
}

/** The time grid of the day and week views: where each timed event sits and how wide. */
object DayLayout {
    /**
     * Where the grid's scroll goes when its range becomes [max]: to [target] (the day's start,
     * 08:00), held within the range, as long as nothing moved it since it was last [placed] there
     * (null: never yet). Null leaves it where it is.
     *
     * The range isn't final at the first layout: placing it once then stopped the grid wherever
     * that early, shorter range ended (04:00 on the owner's phone), and nothing put it back.
     */
    fun openAt(max: Int, value: Int, placed: Int?, target: Int): Int? = when {
        max <= 0 -> null
        placed == null || value == placed -> target.coerceIn(0, max)
        else -> null
    }

    /** An event on one day's grid: minutes from the top by the clock face, and its column of [columns]. */
    data class Placed(val occurrence: Occurrence, val startMin: Int, val endMin: Int, val column: Int, val columns: Int)

    /** The shortest an event is drawn, so a five-minute one can still be tapped. */
    const val MIN_MINUTES = 30

    fun day(day: LocalDate, occurrences: List<Occurrence>, zone: ZoneId): List<Placed> {
        val timed = occurrences.filter { !it.allDay && !it.firstDay.isAfter(day) && !it.lastDay.isBefore(day) }
            .map { o ->
                val s = ClockMinutes.ofDay(o.start.atZone(zone), day)
                val e = ClockMinutes.ofDay(o.end.atZone(zone), day)
                Triple(o, s, maxOf(e, s + MIN_MINUTES).coerceAtMost(ClockMinutes.DAY).let { if (it <= s) s + 1 else it })
            }
            .sortedWith(compareBy({ it.second }, { -it.third }, { it.first.title }))
        val out = mutableListOf<Placed>()
        var cluster = mutableListOf<Triple<Occurrence, Int, Int>>()
        var clusterEnd = -1
        fun flush() {
            if (cluster.isEmpty()) return
            val columnEnds = mutableListOf<Int>()
            val cols = cluster.map { (_, s, e) ->
                var c = columnEnds.indexOfFirst { it <= s }
                if (c < 0) {
                    columnEnds += e
                    c = columnEnds.size - 1
                } else {
                    columnEnds[c] = e
                }
                c
            }
            cluster.forEachIndexed { i, (o, s, e) -> out += Placed(o, s, e, cols[i], columnEnds.size) }
            cluster = mutableListOf()
        }
        for (t in timed) {
            if (t.second >= clusterEnd) flush()
            cluster += t
            clusterEnd = maxOf(clusterEnd, t.third)
        }
        flush()
        return out
    }

    /** The day's all-day events and timed ones running across it from another day. */
    fun allDay(day: LocalDate, occurrences: List<Occurrence>): List<Occurrence> =
        occurrences.filter { it.allDay && !it.firstDay.isAfter(day) && !it.lastDay.isBefore(day) }.sortedWith(compareBy({ it.firstDay }, { it.title }))
}

/** The year view: which calendar's colour tints each day (One Calendar and the laptop's year). */
object YearLayout {
    /**
     * For each day of [year] with events, the calendar with the most of them that day; a tie goes
     * to the calendar whose event comes first in [occurrences]' order (the views' order: all-day
     * first, then by start). An event counts on every day it covers.
     */
    fun tints(occurrences: List<Occurrence>, year: Int): Map<LocalDate, String> {
        val first = LocalDate.of(year, 1, 1)
        val last = LocalDate.of(year, 12, 31)
        val counts = HashMap<LocalDate, LinkedHashMap<String, Int>>()
        for (o in occurrences) {
            if (o.lastDay.isBefore(first) || o.firstDay.isAfter(last)) continue
            for (d in AllDay.days(o.firstDay, o.lastDay, first..last)) {
                if (d.isBefore(first) || d.isAfter(last)) continue
                val day = counts.getOrPut(d) { LinkedHashMap() }
                day[o.calendarId] = (day[o.calendarId] ?: 0) + 1
            }
        }
        // maxBy keeps the first of equals, which is the calendar met first.
        return counts.mapValues { (_, byCalendar) -> byCalendar.entries.maxBy { it.value }.key }
    }

    /** The twelve months, each as its weeks from [firstDay], as many as it needs. */
    fun months(year: Int, firstDay: DayOfWeek): List<List<List<LocalDate>>> =
        (1..12).map { MonthLayout.weeks(YearMonth.of(year, it), firstDay, rows = null) }
}
