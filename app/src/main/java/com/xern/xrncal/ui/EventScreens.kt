package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.EventReminder
import com.xern.xrncal.domain.Frequency
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Repeat
import com.xern.xrncal.domain.RepeatEnd
import com.xern.xrncal.domain.RepeatRules
import com.xern.xrncal.domain.Reminders
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// ---------------- detail ----------------

/** What the detail shows of an event beyond its occurrence. */
data class DetailInfo(
    val calendar: CalendarInfo?,
    val zone: ZoneId?,
    val rule: String?,
    val reminders: List<EventReminder>,
    val problem: String?,
)

/**
 * An event's detail: the app bar in its calendar's colour with back, edit and delete; then the
 * title, the date and time, the lunar date, the repeat, the reminders, the calendar, the place and
 * the notes. An event that couldn't be sent shows why, with Keep mine and Keep theirs.
 */
@Composable
fun DetailScreen(
    o: Occurrence,
    info: DetailInfo,
    look: Look,
    onBack: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onKeepMine: () -> Unit,
    onKeepTheirs: () -> Unit,
) {
    val ink = LocalInk.current
    val bar = look.color(o)
    val fg = onColor(bar)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(ink.screen)) {
        Column(Modifier.fillMaxWidth().background(bar).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.AutoMirrored.Filled.ArrowBack, onBack, fg)
                Spacer(Modifier.weight(1f))
                if (onEdit != null) BarIcon(Icons.Filled.Edit, onEdit, fg)
                if (onDelete != null) {
                    Box {
                        BarIcon(Icons.Filled.MoreVert, { menu = true }, fg)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Delete", style = type(15)) }, onClick = {
                                menu = false
                                onDelete()
                            })
                        }
                    }
                }
            }
            Text(o.title.ifBlank { "(no title)" }, style = type(24, FontWeight.Medium, fg), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).navigationBarsPadding()) {
            if (info.problem != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(8.dp)).background(ink.field).padding(12.dp)) {
                    Text(problemLabel(info.problem), style = type(15, FontWeight.SemiBold, ink.warn))
                    Row {
                        TextButton(onClick = onKeepMine) { Text("Keep mine", style = type(14, FontWeight.Medium, ink.accent)) }
                        TextButton(onClick = onKeepTheirs) { Text("Keep theirs", style = type(14, FontWeight.Medium, ink.accent)) }
                    }
                }
            }
            val zoned = info.zone?.takeIf { !o.allDay && it.rules.getOffset(o.start) != look.zone.rules.getOffset(o.start) }
            DetailLine(
                icon = Icons.Outlined.Schedule,
                main = if (o.allDay) (if (o.multiDay) "${Format.dateButton(o.firstDay)} – ${Format.dateButton(o.lastDay)}" else Format.dateButton(o.firstDay))
                else if (o.multiDay) Format.span(o, look.h24, look.zone) else Format.dateButton(o.firstDay),
                side = if (o.allDay) null else if (o.multiDay) null else Format.span(o, look.h24, look.zone),
            )
            if (zoned != null) DetailLine(Format.span(o, look.h24, zoned), zoned.id, Icons.Outlined.Language)
            if (look.lunar) DetailLine("Âm lịch " + Format.lunarLong(o.firstDay), null, Icons.Outlined.DarkMode)
            info.rule?.let { DetailLine(RepeatRules.describe(it, o.firstDay), null, Icons.Outlined.Repeat) }
            info.reminders.forEachIndexed { i, r -> DetailLine(Reminders.label(r.minutesBefore, o.allDay), null, if (i == 0) Icons.Outlined.NotificationsNone else null) }
            info.calendar?.let { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { Swatch(Color(c.color)) }
                    Spacer(Modifier.width(16.dp))
                    Text(c.name, style = type(16, color = ink.text))
                }
            }
            if (o.location.isNotBlank()) DetailLine(o.location, null, Icons.Outlined.Place)
            if (o.notes.isNotBlank()) {
                HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 8.dp))
                Text(o.notes, style = type(15, color = ink.text), modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

fun problemLabel(p: String): String = when (p) {
    "conflict" -> "Changed on the server too"
    "gone" -> "Deleted on the server"
    else -> p
}

@Composable
private fun DetailLine(main: String, side: String?, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink.muted, modifier = Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(main, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
        if (side != null) Text(side, style = type(16, color = ink.text))
    }
}

/** "This event" or "All events", asked before editing or deleting a date of a series. */
@Composable
fun ScopeDialog(title: String, onThis: () -> Unit, onAll: () -> Unit, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = type(18, FontWeight.SemiBold, ink.text)) },
        text = {
            Column {
                Text("This event", style = type(16, color = ink.text), modifier = Modifier.fillMaxWidth().clickable(onClick = onThis).padding(vertical = 12.dp))
                Text("All events", style = type(16, color = ink.text), modifier = Modifier.fillMaxWidth().clickable(onClick = onAll).padding(vertical = 12.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = type(14, FontWeight.Medium, ink.accent)) } },
    )
}

// ---------------- editor ----------------

/** The editor's fields while they are being changed. */
@Stable
class EditorState(draft: EventDraft, calendarId: String) {
    var title by mutableStateOf(draft.title)
    var calendarId by mutableStateOf(calendarId)
    var allDay by mutableStateOf(draft.allDay)
    var startDate by mutableStateOf(draft.startDate)
    var startTime by mutableStateOf(draft.startTime)
    var endDate by mutableStateOf(draft.endDate)
    var endTime by mutableStateOf(draft.endTime)
    var zone by mutableStateOf(draft.zone)
    var rule by mutableStateOf(draft.rrule)
    var reminders by mutableStateOf(draft.reminders)
    var location by mutableStateOf(draft.location)
    var notes by mutableStateOf(draft.notes)
    var color by mutableStateOf(draft.color)

    fun draft() = EventDraft(title, allDay, startDate, startTime, endDate, endTime, zone, rule?.let { RepeatRules.fitUntil(it, allDay, zone) }, reminders, location, notes, color)

    /** A new start moves the end with it, so the event keeps its length. */
    fun moveStart(date: LocalDate, time: LocalTime) {
        val before = java.time.LocalDateTime.of(startDate, startTime)
        val after = java.time.LocalDateTime.of(date, time)
        val length = Duration.between(before, java.time.LocalDateTime.of(endDate, endTime))
        startDate = date
        startTime = time
        val end = after.plus(if (length.isNegative) Duration.ZERO else length)
        endDate = end.toLocalDate()
        endTime = end.toLocalTime()
    }
}

/**
 * The editor, as One Calendar lays it out: the app bar in the chosen calendar's colour with ✕ and
 * ✓, then Subject, Calendar, All day, Start and End (date, time, time zone), Repeat, Location,
 * Reminders and Notes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    adding: Boolean,
    calendars: List<CalendarInfo>,
    look: Look,
    defaults: Reminders.Defaults,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val ink = LocalInk.current
    val cal = calendars.firstOrNull { it.id == state.calendarId }
    val bar = Color(cal?.color ?: 0xFF308FFE.toInt())
    val fg = onColor(bar)
    val problem = state.draft().problem()
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(ink.screen).imePadding()) {
        Row(Modifier.fillMaxWidth().background(bar).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.Filled.Close, onCancel, fg)
            Text(if (adding) "Add" else "Edit", style = type(20, FontWeight.SemiBold, fg), modifier = Modifier.padding(start = 8.dp).weight(1f))
            BarIcon(Icons.Filled.Check, { if (problem == null) onSave() }, if (problem == null) fg else fg.copy(alpha = 0.4f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding()) {
            Label("Subject")
            Field(state.title, { state.title = it }, Modifier.padding(horizontal = 16.dp))
            Label("Calendar")
            var colorOpen by remember { mutableStateOf(false) }
            Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CalendarPicker(calendars, state.calendarId) { state.calendarId = it } }
                Row(
                    Modifier.height(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable { colorOpen = true }
                        .semantics { contentDescription = "Event colour" }.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(Color(state.color ?: cal?.color ?: 0), 22.dp)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ink.muted)
                }
            }
            if (colorOpen) {
                ColorPickerDialog("Colour", state.color, cal?.color ?: 0, onPick = {
                    state.color = it
                    colorOpen = false
                }, onDismiss = { colorOpen = false })
            }
            Divider()
            SwitchRow("All day", state.allDay) { on ->
                state.allDay = on
                if (on && state.endDate.isBefore(state.startDate)) state.endDate = state.startDate
                if (!on && java.time.LocalDateTime.of(state.endDate, state.endTime) <= java.time.LocalDateTime.of(state.startDate, state.startTime)) {
                    val end = java.time.LocalDateTime.of(state.startDate, state.startTime).plusHours(1)
                    state.endDate = end.toLocalDate()
                    state.endTime = end.toLocalTime()
                }
            }
            Label("Start")
            WhenRow(state.startDate, state.startTime, state.allDay, look, { picking = "startDate" }, { picking = "startTime" }, { picking = "zone" })
            Label("End")
            WhenRow(state.endDate, state.endTime, state.allDay, look, { picking = "endDate" }, { picking = "endTime" }, { picking = "zone" })
            if (problem != null) Text(problem, style = type(13, color = ink.warn), modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            if (!state.allDay && state.zone != look.zone) Text(state.zone.id, style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            Divider()
            RepeatSection(state)
            Divider()
            Label("Location")
            Field(state.location, { state.location = it }, Modifier.padding(horizontal = 16.dp))
            Label("Reminders")
            ReminderSection(state)
            Label("Notes")
            Field(state.notes, { state.notes = it }, Modifier.padding(horizontal = 16.dp).heightIn(min = 96.dp), singleLine = false)
        }
    }
    when (picking) {
        "startDate" -> DatePick(state.startDate, { picking = null }) { state.moveStart(it, state.startTime) }
        "endDate" -> DatePick(state.endDate, { picking = null }) { state.endDate = it }
        "startTime" -> TimePick(state.startTime, look.h24, { picking = null }) { state.moveStart(state.startDate, it) }
        "endTime" -> TimePick(state.endTime, look.h24, { picking = null }) { state.endTime = it }
        "zone" -> ZonePick(state.zone, { picking = null }) { state.zone = it }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = type(13, color = LocalInk.current.muted), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = LocalInk.current.line, modifier = Modifier.padding(top = 14.dp))

@Composable
fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, number: Boolean = false, password: Boolean = false) {
    val ink = LocalInk.current
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = type(16, color = ink.text),
        keyboardOptions = KeyboardOptions(keyboardType = when {
            number -> KeyboardType.Number
            password -> KeyboardType.Password
            else -> KeyboardType.Text
        }),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = ink.field, unfocusedContainerColor = ink.field,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = ink.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Picker(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val ink = LocalInk.current
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = type(16, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ink.muted)
    }
}

@Composable
private fun CalendarPicker(calendars: List<CalendarInfo>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cal = calendars.firstOrNull { it.id == selected }
    Box(Modifier.padding(horizontal = 16.dp)) {
        Picker(cal?.name ?: "", { open = true }, Modifier.fillMaxWidth(), leading = {
            Swatch(Color(cal?.color ?: 0))
            Spacer(Modifier.width(12.dp))
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in calendars) {
                DropdownMenuItem(
                    leadingIcon = { Swatch(Color(c.color)) },
                    text = { Text(c.name, style = type(15)) },
                    onClick = {
                        open = false
                        onPick(c.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = type(17, color = ink.text), modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent))
    }
}

@Composable
private fun WhenRow(date: LocalDate, time: LocalTime, allDay: Boolean, look: Look, onDate: () -> Unit, onTime: () -> Unit, onZone: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Picker(Format.dateButton(date), onDate, Modifier.weight(1.4f))
        if (!allDay) {
            Picker(Format.time(time, look.h24), onTime, Modifier.weight(0.9f))
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable(onClick = onZone), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Language, contentDescription = null, tint = ink.muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatSection(state: EditorState) {
    val ink = LocalInk.current
    SwitchRow("Repeat", state.rule != null) { on -> state.rule = if (on) "FREQ=WEEKLY" else null }
    val rule = state.rule ?: return
    val preset = RepeatRules.preset(rule, state.startDate)
    var custom by remember { mutableStateOf(preset == RepeatRules.Preset.CUSTOM) }
    val presets = listOf(
        RepeatRules.Preset.DAILY to "Every day", RepeatRules.Preset.WEEKDAYS to "Weekdays", RepeatRules.Preset.WEEKLY to "Every week",
        RepeatRules.Preset.MONTHLY to "Every month", RepeatRules.Preset.YEARLY to "Every year", RepeatRules.Preset.CUSTOM to "Custom",
    )
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((p, label) in presets) {
            val on = if (custom) p == RepeatRules.Preset.CUSTOM else p == preset
            Text(
                label,
                style = type(14, FontWeight.Medium, if (on) Color.White else ink.text),
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) ink.accent else ink.field).clickable {
                    if (p == RepeatRules.Preset.CUSTOM) {
                        custom = true
                    } else {
                        custom = false
                        state.rule = RepeatRules.rule(RepeatRules.ofPreset(p, state.startDate)!!, state.startDate, state.allDay, state.zone)
                    }
                }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    if (custom) CustomRepeat(state, rule)
}

private fun unit(f: Frequency, plural: Boolean) = when (f) {
    Frequency.DAILY -> "day"
    Frequency.WEEKLY -> "week"
    Frequency.MONTHLY -> "month"
    Frequency.YEARLY -> "year"
} + if (plural) "s" else ""

/** Every N days, weeks (on which days), months (on the date or the nth weekday) or years, and the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeat(state: EditorState, rule: String) {
    val ink = LocalInk.current
    val r = RepeatRules.parse(rule, state.startDate)
    if (r == null) {
        Text(rule, style = type(13, color = ink.muted), modifier = Modifier.padding(horizontal = 20.dp))
        return
    }
    fun set(next: Repeat) {
        state.rule = RepeatRules.rule(next, state.startDate, state.allDay, state.zone)
    }
    var unitOpen by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Every", style = type(16, color = ink.text))
        Field(r.interval.toString(), { t -> t.filter(Char::isDigit).take(3).toIntOrNull()?.takeIf { it >= 1 }?.let { set(r.copy(interval = it)) } }, Modifier.width(72.dp), number = true)
        Box(Modifier.weight(1f)) {
            Picker(unit(r.frequency, r.interval > 1), { unitOpen = true })
            DropdownMenu(expanded = unitOpen, onDismissRequest = { unitOpen = false }) {
                for (f in Frequency.entries) {
                    DropdownMenuItem(text = { Text(unit(f, r.interval > 1), style = type(15)) }, onClick = {
                        unitOpen = false
                        set(r.copy(frequency = f, weekdays = if (f == Frequency.WEEKLY) setOf(state.startDate.dayOfWeek) else emptySet(), monthlyByWeekday = false))
                    })
                }
            }
        }
    }
    if (r.frequency == Frequency.WEEKLY) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in DayOfWeek.entries) {
                val on = d in r.weekdays
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (on) ink.accent else ink.field).clickable {
                        val days = if (on) r.weekdays - d else r.weekdays + d
                        if (days.isNotEmpty()) set(r.copy(weekdays = days))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text(Format.weekdayLetter(d), style = type(14, FontWeight.Medium, if (on) Color.White else ink.text)) }
            }
        }
    }
    if (r.frequency == Frequency.MONTHLY) {
        val nth = (state.startDate.dayOfMonth - 1) / 7 + 1
        val ord = listOf("first", "second", "third", "fourth", "last").getOrElse(nth - 1) { "last" }
        Choice("On day ${state.startDate.dayOfMonth}", !r.monthlyByWeekday) { set(r.copy(monthlyByWeekday = false)) }
        Choice("On the $ord ${state.startDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}", r.monthlyByWeekday) { set(r.copy(monthlyByWeekday = true)) }
    }
    Label("Ends")
    var untilPick by remember { mutableStateOf(false) }
    Choice("Never", r.end == RepeatEnd()) { set(r.copy(end = RepeatEnd())) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Choice("On", r.end.until != null, Modifier.width(124.dp)) { set(r.copy(end = RepeatEnd(until = r.end.until ?: state.startDate.plusMonths(1)))) }
        r.end.until?.let { u -> Picker(Format.dateButton(u), { untilPick = true }, Modifier.padding(end = 16.dp).weight(1f)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Choice("After", r.end.count != null, Modifier.width(124.dp)) { set(r.copy(end = RepeatEnd(count = r.end.count ?: 10))) }
        r.end.count?.let { count ->
            Field(count.toString(), { t -> t.filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it >= 1 }?.let { set(r.copy(end = RepeatEnd(count = it))) } }, Modifier.width(88.dp), number = true)
            Text("  times", style = type(16, color = ink.text))
        }
    }
    if (untilPick) DatePick(r.end.until ?: state.startDate, { untilPick = false }) { set(r.copy(end = RepeatEnd(until = it))) }
}

@Composable
private fun Choice(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) ink.accent else ink.field), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = type(16, color = ink.text))
    }
}

@Composable
private fun ReminderSection(state: EditorState) {
    val ink = LocalInk.current
    // No VALARM and none chosen are the same here: neither rings.
    val list = state.reminders.orEmpty()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (list.isEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(ink.field).padding(start = 12.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("None", style = type(16, color = ink.text), modifier = Modifier.weight(1f))
            }
        }
        for (r in list) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(ink.field).padding(start = 12.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Reminders.choiceLabel(r.minutesBefore) + if (r.email) " (email)" else "", style = type(16, color = ink.text), modifier = Modifier.weight(1f))
                IconButton(onClick = { state.reminders = list - r }) { Icon(Icons.Filled.Close, contentDescription = "Remove reminder", tint = ink.muted) }
            }
        }
        Box {
            Text("+ Reminder", style = type(15, FontWeight.Medium, ink.accent), modifier = Modifier.clickable { adding = true }.padding(vertical = 8.dp, horizontal = 4.dp))
            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                for (m in Reminders.ONE_CALENDAR_CHOICES) {
                    DropdownMenuItem(text = { Text(Reminders.choiceLabel(m), style = type(15)) }, onClick = {
                        adding = false
                        state.reminders = Reminders.normalize(list + EventReminder(m))
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val ink = LocalInk.current
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK", style = type(14, FontWeight.Medium, ink.accent)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = type(14, FontWeight.Medium, ink.accent)) } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePick(initial: LocalTime, h24: Boolean, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val ink = LocalInk.current
    val state = rememberTimePickerState(initial.hour, initial.minute, h24)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("OK", style = type(14, FontWeight.Medium, ink.accent)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = type(14, FontWeight.Medium, ink.accent)) } },
        text = { TimePicker(state = state) },
    )
}

/** Every zone the phone knows, found by any part of its name. */
@Composable
private fun ZonePick(current: ZoneId, onDismiss: () -> Unit, onPick: (ZoneId) -> Unit) {
    val ink = LocalInk.current
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() + "UTC" }
    val shown = all.filter { it.replace('_', ' ').contains(query.trim(), ignoreCase = true) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.sheet).padding(vertical = 12.dp)) {
            Field(query, { query = it }, Modifier.padding(horizontal = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(shown) { id ->
                    val offset = ZoneId.of(id).rules.getOffset(Instant.now())
                    Row(Modifier.fillMaxWidth().clickable {
                        onPick(ZoneId.of(id))
                        onDismiss()
                    }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(id.replace('_', ' '), style = type(15, if (id == current.id) FontWeight.SemiBold else FontWeight.Normal, ink.text), modifier = Modifier.weight(1f))
                        Text(if (offset.totalSeconds == 0) "UTC" else "UTC$offset", style = type(13, color = ink.muted))
                    }
                }
            }
        }
    }
}

/** A new event's draft: the next whole hour (or [hour] on [day]), as long as Settings say. */
fun newDraft(day: LocalDate, hour: Int?, now: Instant, zone: ZoneId, minutes: Int, reminders: List<EventReminder>? = null): EventDraft {
    val local = now.atZone(zone)
    val startTime = when {
        hour != null -> LocalTime.of(hour, 0)
        day == local.toLocalDate() -> local.toLocalTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        else -> LocalTime.of(12, 0)
    }
    val start = if (hour == null && day == local.toLocalDate() && startTime == LocalTime.MIDNIGHT) day.plusDays(1).atTime(0, 0) else day.atTime(startTime)
    val end = start.plusMinutes(minutes.toLong())
    return EventDraft("", false, start.toLocalDate(), start.toLocalTime(), end.toLocalDate(), end.toLocalTime(), zone, reminders = reminders)
}
