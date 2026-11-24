package com.xern.xrncal.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Map
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Places
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
    /** A timed chip in the month leads with its start time. */
    val monthTimes: Boolean = true,
    /** Shown under each hour in the day and week gutters, when it differs from [zone]. */
    val secondZone: ZoneId? = null,
) {
    fun color(o: Occurrence): Color = Color(o.color ?: colors[o.calendarId] ?: 0xFF529CCA.toInt())

    /** A month chip's start time, as the laptop leads a timed event's chip with it ("10:30"), or null. */
    fun chipTime(o: Occurrence, day: LocalDate): String? =
        if (!monthTimes || o.allDay || o.firstDay != day) null else Format.time(o.start.atZone(zone).toLocalTime(), h24)

    /** A month chip's text: "10:30 Dentist" for a timed event on its first day, else the title. */
    fun chipText(o: Occurrence, day: LocalDate): String =
        listOfNotNull(chipTime(o, day), o.title.ifBlank { "(no title)" }).joinToString(" ")
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
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Today" }) {
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

/** An icon button in a bar, named for TalkBack and the tests by [description]. */
@Composable
fun BarIcon(icon: ImageVector, onClick: () -> Unit, tint: Color = LocalInk.current.text, description: String) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = tint) }
}

@Composable
fun Swatch(color: Color, size: Dp = 14.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(3.dp)).background(color))
}

/**
 * An event as a full-width card in its colour: title, then the time and the place (the list's
 * rows). With [onMap], a card with a place has a map button that sends the place to a maps app.
 */
@Composable
fun EventCard(o: Occurrence, look: Look, day: LocalDate, onClick: () -> Unit, shape: RoundedCornerShape = RoundedCornerShape(4.dp), onMap: (() -> Unit)? = null) {
    val bg = look.color(o)
    val fg = onColor(bg)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(o.title.ifBlank { "(no title)" }, style = type(17, FontWeight.Medium, fg), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val time = when {
                o.allDay -> if (o.multiDay) Format.span(o, look.h24, look.zone) else ""
                o.multiDay -> segmentLabel(o, day, look)
                else -> Format.span(o, look.h24, look.zone)
            }
            val line = listOf(time, o.location).filter { it.isNotBlank() }.joinToString("  ")
            if (line.isNotEmpty()) Text(line, style = type(14, color = fg.copy(alpha = 0.92f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMap != null && o.location.isNotBlank()) {
            IconButton(onClick = onMap, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Map, contentDescription = "Map", tint = fg) }
        }
    }
}

/**
 * [location] in the owner's maps app, as One Calendar's "Navigation": Android's `geo:` address,
 * to the default app for it or the chooser; none is named here.
 */
fun openMap(context: Context, location: String) {
    val uri = Places.geoUri(location) ?: return
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app", Toast.LENGTH_SHORT).show()
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

/**
 * One Calendar's colour choices (Google's palette) as a grid of round swatches; the current one is
 * ticked. With [calendarColor] an event's picker leads with its calendar's colour, which picks
 * none of its own (null).
 */
@Composable
fun ColorPickerDialog(title: String, current: Int?, calendarColor: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.sheet).padding(vertical = 16.dp, horizontal = 12.dp)) {
            Text(title, style = type(18, FontWeight.SemiBold, ink.text), modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            if (calendarColor != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(null) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(Color(calendarColor), current == null, "Calendar colour")
                    Spacer(Modifier.width(14.dp))
                    Text("Calendar colour", style = type(16, color = ink.text))
                }
            }
            val swatches = com.xern.xrncal.domain.Palette.ALL
            for (row in swatches.chunked(6)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                    for (sw in row) {
                        Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onPick(sw.argb) }.padding(4.dp)) {
                            ColorDot(Color(sw.argb), current != null && (current or 0xFF000000.toInt()) == sw.argb, sw.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, name: String) {
    Box(
        Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = onColor(color))
    }
}
