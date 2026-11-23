package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xern.xrncal.domain.Agenda
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * One Calendar's list: a compact month pinned on top (week numbers, one-letter weekdays, and above
 * each date thin bars in its events' colours, one bar across the days of a span), then every day
 * with events, each under a "24 FRIDAY" header (today's red, with its lunar date), a month header
 * when the month changes, and the day's events as one block of cards in their colours.
 */
@Composable
fun ListPage(
    month: YearMonth,
    firstDay: DayOfWeek,
    monthEvents: List<Occurrence>,
    listEvents: List<Occurrence>,
    from: LocalDate,
    to: LocalDate,
    look: Look,
    onDate: (LocalDate) -> Unit,
    onEvent: (Occurrence) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LocalInk.current.page)) {
        MiniMonth(month, firstDay, monthEvents, look, onDate)
        AgendaList(listEvents, from, to, look, onEvent)
    }
}

/** The days with events from [from] to [to], under their headers. */
@Composable
fun AgendaList(listEvents: List<Occurrence>, from: LocalDate, to: LocalDate, look: Look, onEvent: (Occurrence) -> Unit, state: LazyListState = rememberLazyListState()) {
    val ink = LocalInk.current
    val days = Agenda.group(listEvents, from, to)
    LazyColumn(Modifier.fillMaxSize().background(ink.page), state = state) {
        var lastMonth: YearMonth? = null
        for (day in days) {
            val ym = YearMonth.from(day.date)
            if (lastMonth != null && ym != lastMonth) {
                item(key = "m$ym") {
                    Text(Format.monthUpper(day.date, look.today), style = type(24, color = ink.text), modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 2.dp))
                }
            }
            lastMonth = ym
            item(key = "d${day.date}") {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(Format.listDay(day.date), style = type(15, FontWeight.Medium, if (day.date == look.today) ink.today else ink.text))
                    Spacer(Modifier.weight(1f))
                    if (look.lunar) LunarMark(day.date, Format.lunar(day.date), type(12, color = ink.lunar))
                }
            }
            item(key = "e${day.date}") {
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(4.dp)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (o in day.items) EventCard(o, look, day.date, { onEvent(o) }, RoundedCornerShape(0.dp))
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(80.dp)) }
    }
}

/** The pinned month above the list. */
@Composable
fun MiniMonth(month: YearMonth, firstDay: DayOfWeek, events: List<Occurrence>, look: Look, onDate: (LocalDate) -> Unit) {
    val ink = LocalInk.current
    val weeks = MonthLayout.weeks(month, firstDay, rows = null)
    Column(Modifier.fillMaxWidth().background(ink.bar).padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (look.weekNumbers) Spacer(Modifier.width(30.dp))
            for (d in WeekStart.order(firstDay)) {
                Text(Format.weekdayLetter(d), style = type(12, color = ink.muted), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        for (week in weeks) {
            val layout = MonthLayout.week(week, events, lanes = 3)
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                if (look.weekNumbers) Text("${WeekStart.isoWeekOfRow(week.first())}", style = type(11, color = ink.muted), textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
                BoxWithConstraints(Modifier.weight(1f).height(30.dp)) {
                    val cell = maxWidth / 7
                    for (bar in layout.bars) {
                        Box(
                            Modifier.offset(x = cell * bar.from + 8.dp, y = (2 + bar.lane * 4).dp)
                                .width(cell * (bar.to - bar.from + 1) - 16.dp).height(3.dp)
                                .clip(RoundedCornerShape(2.dp)).background(look.color(bar.occurrence)),
                        )
                    }
                    Row(Modifier.fillMaxSize()) {
                        for (d in week) {
                            val today = d == look.today
                            Box(Modifier.weight(1f).height(30.dp).clickable { onDate(d) }, contentAlignment = Alignment.BottomCenter) {
                                Text(
                                    "${d.dayOfMonth}",
                                    style = type(16, if (today) FontWeight.Bold else FontWeight.Normal, when {
                                        today -> ink.today
                                        d.month == month.month -> ink.text
                                        else -> ink.faint
                                    }),
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
