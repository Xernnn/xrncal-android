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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * The week and day views: the days' headers (weekday, number, lunar date), the all-day row, and
 * the hour grid with each event where its clock times put it, side by side where they overlap,
 * and a line at the current time. Tapping an empty hour starts an event there.
 */
@Composable
fun TimeGridPage(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    look: Look,
    now: Instant,
    onEvent: (Occurrence) -> Unit,
    onSlot: (LocalDate, Int) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    hourHeight: Dp = if (days.size == 1) 60.dp else 52.dp,
) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.page)) {
        // Headers.
        if (days.size == 1) {
            val d = days.single()
            Row(Modifier.fillMaxWidth().background(ink.bar).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(Format.dayLong(d), style = type(16, FontWeight.Medium, if (d == look.today) ink.today else ink.text))
                Spacer(Modifier.weight(1f))
                if (look.lunar) Text(Format.lunarLong(d), style = type(13, color = ink.muted))
            }
        } else {
            Row(Modifier.fillMaxWidth().background(ink.bar).padding(bottom = 6.dp)) {
                Box(Modifier.width(GUTTER), contentAlignment = Alignment.Center) {
                    if (look.weekNumbers) Text("${WeekStart.isoWeekOfRow(days.first())}", style = type(12, color = ink.muted))
                }
                for (d in days) {
                    val today = d == look.today
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Format.weekdayShort(d.dayOfWeek), style = type(12, color = if (today) ink.today else ink.muted))
                        Text("${d.dayOfMonth}", style = type(18, if (today) FontWeight.Bold else FontWeight.Normal, if (today) ink.today else ink.text))
                        if (look.lunar) Text(Format.lunar(d), style = type(10, color = ink.lunar))
                    }
                }
            }
        }
        AllDayRow(days, occurrences, look, onEvent)
        HorizontalDivider(color = ink.line)
        // The hours.
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                Column(Modifier.width(GUTTER)) {
                    for (h in 0 until 24) {
                        Box(Modifier.height(hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            if (h > 0) Text(Format.hour(h, look.h24), style = type(11, color = ink.muted), modifier = Modifier.offset(y = (-7).dp))
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
                            Box(
                                Modifier
                                    .offset(x = col * i + w * p.column + 1.dp, y = hourHeight * (p.startMin / 60f) + 1.dp)
                                    .width(w - 2.dp)
                                    .height(hourHeight * ((p.endMin - p.startMin) / 60f) - 2.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(bg)
                                    .clickable { onEvent(p.occurrence) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Column {
                                    Text(p.occurrence.title.ifBlank { "(no title)" }, style = chip(if (days.size == 1) 14 else 11, onColor(bg)), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (days.size == 1 && p.endMin - p.startMin >= 45) {
                                        Text(listOf(segmentLabel(p.occurrence, d, look), p.occurrence.location).filter { it.isNotBlank() }.joinToString("  "), style = type(12, color = onColor(bg).copy(alpha = 0.9f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** All-day events and spans: bars across the days they cover (the week), or one under another (a day). */
@Composable
private fun AllDayRow(days: List<LocalDate>, occurrences: List<Occurrence>, look: Look, onEvent: (Occurrence) -> Unit) {
    val ink = LocalInk.current
    val allDay = occurrences.filter { it.allDay }
        .filter { o -> days.any { !o.firstDay.isAfter(it) && !o.lastDay.isBefore(it) } }
    if (allDay.isEmpty()) return
    if (days.size == 1) {
        Column(Modifier.fillMaxWidth().background(ink.page).padding(start = GUTTER, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
