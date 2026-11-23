package com.xern.xrncal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xern.xrncal.domain.Occurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/** What the screens draw with: the calendars' colours, today, the clock and the lunar switch. */
data class Look(
    val colors: Map<String, Int> = emptyMap(),
    val today: LocalDate = LocalDate.now(),
    val h24: Boolean = true,
    val lunar: Boolean = true,
    val weekNumbers: Boolean = true,
    val zone: ZoneId = ZoneId.systemDefault(),
    val readOnly: Set<String> = emptySet(),
) {
    fun color(o: Occurrence): Color = Color(o.color ?: colors[o.calendarId] ?: 0xFF529CCA.toInt())
}

/** Now, moving on every minute: today and the time line follow the clock while the app stays open. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now.toEpochMilli() % 60_000L)
            now = Instant.now()
        }
    }
    return now
}

/** One Calendar's Today button: a calendar glyph with today's day number in it. */
@Composable
fun TodayButton(day: Int, onClick: () -> Unit) {
    val ink = LocalInk.current
    IconButton(onClick = onClick) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(24.dp)) {
                val stroke = 1.8.dp.toPx()
                drawRoundRect(ink.text, Offset(stroke / 2, 3.dp.toPx()), Size(size.width - stroke, size.height - 3.dp.toPx() - stroke / 2), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
                drawLine(ink.text, Offset(7.dp.toPx(), 0f), Offset(7.dp.toPx(), 5.dp.toPx()), stroke)
                drawLine(ink.text, Offset(size.width - 7.dp.toPx(), 0f), Offset(size.width - 7.dp.toPx(), 5.dp.toPx()), stroke)
            }
            Text("$day", style = type(11, FontWeight.Bold, ink.text), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun BarIcon(icon: ImageVector, onClick: () -> Unit, tint: Color = LocalInk.current.text) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = null, tint = tint) }
}

@Composable
fun Swatch(color: Color, size: Dp = 14.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(3.dp)).background(color))
}

/** An event as a full-width card in its colour: title, then the time and the place (the list's rows). */
@Composable
fun EventCard(o: Occurrence, look: Look, day: LocalDate, onClick: () -> Unit, shape: RoundedCornerShape = RoundedCornerShape(4.dp)) {
    val bg = look.color(o)
    val fg = onColor(bg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(o.title.ifBlank { "(no title)" }, style = type(17, FontWeight.Medium, fg), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val time = when {
            o.allDay -> if (o.multiDay) Format.span(o, look.h24, look.zone) else ""
            o.multiDay -> segmentLabel(o, day, look)
            else -> Format.span(o, look.h24, look.zone)
        }
        val line = listOf(time, o.location).filter { it.isNotBlank() }.joinToString("  ")
        if (line.isNotEmpty()) Text(line, style = type(14, color = fg.copy(alpha = 0.92f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A timed event running across days, as the part of it on [day]: `18:00 – 00:00`, `00:00 – 07:00`. */
fun segmentLabel(o: Occurrence, day: LocalDate, look: Look): String {
    val s = o.start.atZone(look.zone)
    val e = o.end.atZone(look.zone)
    val from = if (s.toLocalDate() == day) Format.time(s.toLocalTime(), look.h24) else Format.time(java.time.LocalTime.MIDNIGHT, look.h24)
    val to = if (e.toLocalDate() == day) Format.time(e.toLocalTime(), look.h24) else Format.time(java.time.LocalTime.MIDNIGHT, look.h24)
    return "$from – $to"
}

/** A compact row: colour bar, title and time, for the day pop-up and search. */
@Composable
fun EventRow(o: Occurrence, look: Look, onClick: () -> Unit, day: LocalDate? = null, showDate: Boolean = false) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(look.color(o)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title.ifBlank { "(no title)" }, style = type(16, FontWeight.Medium, ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = when {
                showDate -> Format.dayShort(o.firstDay) + if (o.allDay) "" else "  " + Format.time(o.start.atZone(look.zone).toLocalTime(), look.h24)
                day != null && o.multiDay && !o.allDay -> segmentLabel(o, day, look)
                else -> Format.span(o, look.h24, look.zone)
            }
            Text(listOf(time, o.location).filter { it.isNotBlank() }.joinToString("  "), style = type(13, color = ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
