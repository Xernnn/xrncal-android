package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xern.xrncal.domain.Lunar
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val LANE = 18.dp
private val LANE_GAP = 2.dp
private val DAY_ROW = 22.dp
private val WEEK_NUMBERS = 24.dp

/**
 * One month, One Calendar's way: the weekday row; week numbers on the left; six rows sharing the
 * height; in each cell the day number at the top-left (today's in the signal colour, the cell
 * tinted), the lunar date at the top-right, "+N" beside the number counting what doesn't fit, and
 * as many one-line chips as the cell holds, in their colour, a timed one led by its start time as
 * on the laptop; events over several days as one bar.
 */
@Composable
fun MonthPage(
    month: YearMonth,
    firstDay: DayOfWeek,
    occurrences: List<Occurrence>,
    look: Look,
    onDay: (LocalDate) -> Unit,
    onEvent: (Occurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    val weeks = MonthLayout.weeks(month, firstDay)
    Column(modifier.fillMaxSize().background(ink.page)) {
        Row(Modifier.fillMaxWidth().background(ink.bar).padding(bottom = 4.dp)) {
            if (look.weekNumbers) Spacer(Modifier.width(WEEK_NUMBERS))
            for (d in WeekStart.order(firstDay)) {
                Text(Format.weekdayShort(d), style = type(13, color = ink.muted), modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rowHeight = maxHeight / weeks.size
            val lanes = MonthLayout.lanes(rowHeight.value - 1f, DAY_ROW.value, LANE.value, LANE_GAP.value)
            Column(Modifier.fillMaxSize()) {
                for (week in weeks) {
                    WeekRow(week, month, occurrences, lanes, look, onDay, onEvent, Modifier.height(rowHeight))
                }
            }
        }
    }
}

@Composable
private fun WeekRow(days: List<LocalDate>, month: YearMonth, occurrences: List<Occurrence>, lanes: Int, look: Look, onDay: (LocalDate) -> Unit, onEvent: (Occurrence) -> Unit, modifier: Modifier) {
    val ink = LocalInk.current
    val layout = MonthLayout.week(days, occurrences, lanes)
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = ink.line, thickness = 1.dp)
        Row(Modifier.fillMaxSize()) {
            if (look.weekNumbers) {
                Box(Modifier.width(WEEK_NUMBERS).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("${WeekStart.isoWeekOfRow(days.first())}", style = type(12, color = ink.muted))
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(ink.line))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cell = maxWidth / 7
                Row(Modifier.fillMaxSize()) {
                    days.forEachIndexed { i, d ->
                        DayCell(d, d.month == month.month, d == look.today, layout.more[i], look, Modifier.weight(1f).fillMaxHeight().clickable { onDay(d) })
                    }
                }
                for (bar in layout.bars) {
                    val o = bar.occurrence
                    val bg = look.color(o)
                    val span = bar.to - bar.from + 1
                    Box(
                        Modifier
                            .offset(x = cell * bar.from + 1.dp, y = DAY_ROW + (LANE + LANE_GAP) * bar.lane)
                            .width(cell * span - 2.dp)
                            .height(LANE)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bg)
                            .clickable { onEvent(o) }
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val time = look.chipTime(o, days[bar.from])
                        Text(
                            buildAnnotatedString {
                                if (time != null) withStyle(SpanStyle(fontSize = 9.sp, fontWeight = FontWeight.Normal)) { append(time + " ") }
                                append(o.title.ifBlank { "(no title)" })
                            },
                            style = type(11, FontWeight.Medium, onColor(bg), lineHeight = 13.sp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, today: Boolean, more: Int, look: Look, modifier: Modifier) {
    val ink = LocalInk.current
    Box(modifier.background(if (today) ink.todayCell else Color.Transparent)) {
        Row(Modifier.fillMaxWidth().height(DAY_ROW).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.dayOfMonth}",
                style = type(15, if (today) FontWeight.Bold else FontWeight.Normal, when {
                    today -> ink.today
                    inMonth -> ink.text
                    else -> ink.faint
                }),
            )
            if (more > 0) Text("+$more", style = type(12, FontWeight.Medium, if (inMonth) ink.text else ink.faint), maxLines = 1, modifier = Modifier.semantics { contentDescription = "$more more" })
            Spacer(Modifier.weight(1f))
            if (look.lunar) {
                val l = Lunar.label(Lunar.ofSolar(d))
                Text(
                    l.label,
                    style = type(10, if (l.isFirstDay || l.isFullMoon) FontWeight.SemiBold else FontWeight.Normal, if (l.isFirstDay || l.isFullMoon) ink.muted else ink.lunar),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One Calendar's day pop-up, opened by tapping a day in the month: the day as a title in the
 * signal colour ("TUESDAY 24", the lunar date beside it), Add appointment, Day view, then the
 * day's events as cards in their colours with their times.
 */
@Composable
fun DayPopup(day: LocalDate, events: List<Occurrence>, look: Look, onEvent: (Occurrence) -> Unit, onAdd: () -> Unit, onDayView: () -> Unit, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(ink.sheet).padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(Format.popupDay(day), style = type(20, color = ink.today), modifier = Modifier.weight(1f))
                if (look.lunar) Text(Format.lunarLong(day), style = type(13, color = ink.muted))
            }
            PopupAction(Icons.Filled.Add, "Add appointment", onAdd)
            PopupAction(Icons.Outlined.CalendarViewDay, "Day view", onDayView)
            if (events.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(2.dp)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp)) {
                    items(events, key = { it.key + it.calendarId }) { o -> EventCard(o, look, day, { onEvent(o) }, RoundedCornerShape(0.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PopupAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ink.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(30.dp))
        Text(label, style = type(16, color = ink.text))
    }
}
