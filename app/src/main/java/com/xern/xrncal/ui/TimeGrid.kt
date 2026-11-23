package com.xern.xrncal.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrncal.domain.ClockMinutes
import com.xern.xrncal.domain.DayLayout
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.WeekStart
import java.time.Instant
import java.time.LocalDate

private val GUTTER = 44.dp
private val ALL_DAY_LANE = 22.dp

/**
 * The week and day views, as One Calendar and the laptop draw them: the days' headers (week: "Mo
 * 23" with the lunar day under it, today in the signal colour; day: the lunar date and the week),
 * the all-day row, and the hour grid at the hour size Options sets, opened at the day's start
 * (08:00). The gutter writes each hour, and under it that hour in the second time zone when there
 * is one. Each event sits where its clock times put it, side by side where they overlap, with its
 * title and then its time range; a line marks the current time. Tapping an empty hour starts an
 * event there.
 */
/**
 * The day and week grids' scroll, opened at [targetPx] (the day's start, 08:00, as One Calendar).
 * Placed again each time the grid's range changes while the layout settles, until the owner
 * scrolls it (DayLayout.openAt).
 */
@Composable
fun rememberDayStartScroll(targetPx: Int): ScrollState {
    val scroll = rememberScrollState()
    LaunchedEffect(targetPx) {
        var placed: Int? = null
        snapshotFlow { scroll.maxValue }.collect { max ->
            com.xern.xrncal.domain.DayLayout.openAt(max, scroll.value, placed, targetPx)?.let {
                scroll.scrollTo(it)
                placed = scroll.value
            }
        }
    }
    return scroll
}

@Composable
fun TimeGridPage(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    look: Look,
    now: Instant,
    onEvent: (Occurrence) -> Unit,
    onSlot: (LocalDate, Int) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    hourHeight: Dp = 40.dp,
    dayStart: Int = 0,
) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.page)) {
        // Headers.
        if (days.size == 1) {
            val d = days.single()
            Row(Modifier.fillMaxWidth().background(ink.bar).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                if (look.lunar) Text(Format.lunarLong(d), style = type(12, color = ink.muted))
                Spacer(Modifier.weight(1f))
                if (look.weekNumbers) Text("week ${WeekStart.isoWeekOfRow(WeekStart.startOfWeek(d, java.time.DayOfWeek.MONDAY))}", style = type(13, color = ink.muted))
            }
        } else {
            Row(Modifier.fillMaxWidth().background(ink.bar).padding(top = 2.dp, bottom = 4.dp)) {
                Spacer(Modifier.width(GUTTER))
                for (d in days) {
                    val today = d == look.today
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Format.weekdayTwo(d.dayOfWeek) + " " + d.dayOfMonth,
                            style = type(14, if (today) FontWeight.Bold else FontWeight.Normal, if (today) ink.today else ink.text),
                            maxLines = 1,
                        )
                        if (look.lunar) Text(Format.lunar(d), style = type(10, color = if (today) ink.today else ink.lunar), maxLines = 1)
                    }
                }
            }
        }
        AllDayRow(days, occurrences, look, onEvent)
        HorizontalDivider(color = ink.line)
        // The hours. One Calendar leaves blank below the last hour when the day's start to midnight
        // is shorter than the screen, so that the grid can open at the start (08:00) on any phone.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = com.xern.xrncal.domain.DayLayout.roomBelow(maxHeight.value, hourHeight.value, dayStart).dp
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                // The 24 hours, and room below midnight so that the day's start can reach the top.
                Spacer(Modifier.fillMaxWidth().height(hourHeight * 24 + room))
                Row(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                    Column(Modifier.width(GUTTER)) {
                        for (h in 0 until 24) {
                            Column(Modifier.height(hourHeight).fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Format.hourLabel(h, look.h24), style = type(11, color = ink.muted), maxLines = 1)
                                look.secondZone?.let { z ->
                                    val there = look.today.atTime(h, 0).atZone(look.zone).withZoneSameInstant(z).toLocalTime()
                                    Text(Format.hourLabel(there.hour, look.h24, there.minute), style = type(9, color = ink.lunar), maxLines = 1)
                                }
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val col = maxWidth / days.size
                        // Lines and empty hours.
                        Column(Modifier.fillMaxSize()) {
                            for (h in 0 until 24) {
                                Box(Modifier.fillMaxWidth().height(hourHeight)) {
                                    HorizontalDivider(color = ink.line)
                                }
                            }
                        }
                        Row(Modifier.fillMaxSize()) {
                            days.forEachIndexed { i, d ->
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(if (d == look.today && days.size > 1) ink.todayCell else androidx.compose.ui.graphics.Color.Transparent)
                                        .pointerInput(d) { detectTapGestures { p -> onSlot(d, (p.y / (hourHeight.toPx())).toInt().coerceIn(0, 23)) } },
                                ) {
                                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(ink.line))
                                }
                            }
                        }
                        // Events.
                        days.forEachIndexed { i, d ->
                            for (p in DayLayout.day(d, occurrences, look.zone)) {
                                val w = col / p.columns
                                val bg = look.color(p.occurrence)
                                val height = hourHeight * ((p.endMin - p.startMin) / 60f) - 2.dp
                                Box(
                                    Modifier
                                        .offset(x = col * i + w * p.column + 1.dp, y = hourHeight * (p.startMin / 60f) + 1.dp)
                                        .width(w - 2.dp)
                                        .height(height)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(bg)
                                        .clickable { onEvent(p.occurrence) }
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                ) {
                                    val single = days.size == 1
                                    val size = if (single) 13 else 11
                                    Column {
                                        Text(p.occurrence.title.ifBlank { "(no title)" }, style = chip(size, onColor(bg), lineHeight = (size + 2).sp), maxLines = if (single) 2 else 4, overflow = TextOverflow.Ellipsis)
                                        // The time range under the title, as the laptop's blocks, when there's room.
                                        if (height >= (if (single) 34.dp else 40.dp)) {
                                            val time = listOf(segmentLabel(p.occurrence, d, look), if (single) p.occurrence.location else "").filter { it.isNotBlank() }.joinToString("  ")
                                            Text(time, style = type(if (single) 12 else 9, color = onColor(bg).copy(alpha = 0.9f), lineHeight = (if (single) 14 else 11).sp), maxLines = if (single) 1 else 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        // The current time.
                        val todayAt = days.indexOf(look.today)
                        if (todayAt >= 0) {
                            val minutes = ClockMinutes.ofDay(now.atZone(look.zone), look.today)
                            val y = hourHeight * (minutes / 60f)
                            val x = if (days.size == 1) 0.dp else col * todayAt
                            val width = if (days.size == 1) maxWidth else col
                            Box(Modifier.offset(x = x, y = y - 1.dp).width(width).height(2.dp).background(ink.today))
                            Box(Modifier.offset(x = x - 4.dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(ink.today))
                        }
                    }
                }
            }
        }
    }
}

/** All-day events and spans: bars across the days they cover (the week), or one under another (a day). */
@Composable
private fun AllDayRow(days: List<LocalDate>, occurrences: List<Occurrence>, look: Look, onEvent: (Occurrence) -> Unit) {
    val ink = LocalInk.current
    val allDay = occurrences.filter { it.allDay }
        .filter { o -> days.any { !o.firstDay.isAfter(it) && !o.lastDay.isBefore(it) } }
    if (allDay.isEmpty()) return
    if (days.size == 1) {
        Column(Modifier.fillMaxWidth().padding(start = GUTTER, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (o in DayLayout.allDay(days.single(), allDay)) AllDayChip(o, look, onEvent, Modifier.fillMaxWidth())
        }
        return
    }
    val week = MonthLayout.week(days, allDay, lanes = 3)
    val lanes = (week.bars.maxOfOrNull { it.lane } ?: 0) + 1
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Spacer(Modifier.width(GUTTER))
        BoxWithConstraints(Modifier.weight(1f).height(ALL_DAY_LANE * lanes + 2.dp * (lanes - 1) + if (week.more.any { it > 0 }) 14.dp else 0.dp)) {
            val col = maxWidth / days.size
            for (b in week.bars) {
                AllDayChip(b.occurrence, look, onEvent, Modifier.offset(x = col * b.from + 1.dp, y = (ALL_DAY_LANE + 2.dp) * b.lane).width(col * (b.to - b.from + 1) - 2.dp))
            }
            week.more.forEachIndexed { i, n ->
                if (n > 0) Text("+$n", style = type(10, FontWeight.Medium, ink.muted), textAlign = TextAlign.Center, modifier = Modifier.offset(x = col * i, y = (ALL_DAY_LANE + 2.dp) * lanes).width(col))
            }
        }
    }
}

@Composable
private fun AllDayChip(o: Occurrence, look: Look, onEvent: (Occurrence) -> Unit, modifier: Modifier) {
    val bg = look.color(o)
    Box(modifier.height(ALL_DAY_LANE).clip(RoundedCornerShape(3.dp)).background(bg).clickable { onEvent(o) }.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(o.title.ifBlank { "(no title)" }, style = type(11, FontWeight.Medium, onColor(bg)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
