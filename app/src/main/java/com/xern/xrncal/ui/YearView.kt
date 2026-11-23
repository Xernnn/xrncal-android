package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrncal.domain.Lunar
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.WeekStart
import com.xern.xrncal.domain.YearLayout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The year, as One Calendar and the laptop draw it: twelve mini months three across, each day with
 * events tinted in the colour of the calendar with the most of them that day, the lunar day small
 * under the date, today in the signal colour. Tapping a day opens it.
 */
@Composable
fun YearPage(year: Int, firstDay: DayOfWeek, occurrences: List<Occurrence>, look: Look, onDay: (LocalDate) -> Unit) {
    val ink = LocalInk.current
    val tints = YearLayout.tints(occurrences, year)
    val months = YearLayout.months(year, firstDay)
    Column(Modifier.fillMaxSize().background(ink.page).padding(horizontal = 2.dp)) {
        for (row in 0 until 4) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 3) {
                    val m = row * 3 + col
                    MiniYearMonth(year, m + 1, months[m], firstDay, tints, look, onDay, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniYearMonth(
    year: Int,
    month: Int,
    weeks: List<List<LocalDate>>,
    firstDay: DayOfWeek,
    tints: Map<LocalDate, String>,
    look: Look,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val ink = LocalInk.current
    val thisMonth = look.today.year == year && look.today.monthValue == month
    Column(modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        Text(
            java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            style = type(13, FontWeight.Medium, if (thisMonth) ink.today else ink.text),
            modifier = Modifier.padding(start = 3.dp, bottom = 1.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            for (d in WeekStart.order(firstDay)) {
                Text(Format.weekdayLetter(d), style = type(8, color = ink.muted), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val rowHeight = maxHeight / 6
            Column {
                for (week in weeks) {
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        for (d in week) {
                            if (d.monthValue != month) {
                                Box(Modifier.weight(1f))
                                continue
                            }
                            val tint = tints[d]?.let { look.colors[it] }?.let { Color(it) }
                            val today = d == look.today
                            Column(
                                Modifier.weight(1f).padding(0.5.dp).clip(RoundedCornerShape(2.dp))
                                    .background(tint?.copy(alpha = 0.85f) ?: Color.Transparent)
                                    .clickable { onDay(d) }
                                    .semantics { contentDescription = Format.dayLong(d) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val fg = when {
                                    today -> if (tint != null) onColor(tint) else ink.today
                                    tint != null -> onColor(tint)
                                    else -> ink.text
                                }
                                Box(
                                    Modifier.then(if (today && tint != null) Modifier.background(ink.today, RoundedCornerShape(2.dp)) else Modifier),
                                ) {
                                    Text("${d.dayOfMonth}", style = type(10, if (today) FontWeight.Bold else FontWeight.Normal, if (today && tint != null) Color.White else fg, lineHeight = 11.sp), textAlign = TextAlign.Center)
                                }
                                if (look.lunar) {
                                    val l = Lunar.label(Lunar.ofSolar(d))
                                    Text(l.label, style = type(6, color = fg.copy(alpha = 0.75f), lineHeight = 7.sp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
