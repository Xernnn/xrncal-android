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
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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

// ---------------- shared ----------------

fun problemLabel(p: String): String = when (p) {
    "conflict" -> "Changed on the server too"
    "gone" -> "Deleted on the server"
    else -> p
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
    /** What the editor opened with: ✓ with nothing changed saves nothing. */
    private val initial = draft
    private val initialCalendar = calendarId

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

    /** What a date, time or repeat typed into a new event's title said (the laptop's natural entry). */
    var entry by mutableStateOf<com.xern.xrncal.domain.ParsedEntry?>(null)
        private set
    /** The fields as they were before the title said anything, to go back to when it no longer does. */
    private var entryBase: EventDraft? = null

    /** The title saved: the typed one without the date and time it gave. */
    val savedTitle: String get() = entry?.title ?: title

    fun draft() = EventDraft(savedTitle, allDay, startDate, startTime, endDate, endTime, zone, rule?.let { RepeatRules.fitUntil(it, allDay, zone) }, reminders, location, notes, color)

    /**
     * The title typed: for a new event ([context] given) the date, time, length and repeat it
     * names fill those fields, from what they were before it named any (so typing on re-reads it).
     */
    fun typeTitle(text: String, context: com.xern.xrncal.domain.EntryContext?, minutes: Int) {
        title = text
        if (context == null) return
        val e = com.xern.xrncal.domain.NaturalEntry.parse(text, context)
        val base = entryBase
        if (e == null) {
            if (base != null) setTimes(base)
            entryBase = null
            entry = null
            return
        }
        val from = base ?: draft().copy(title = text).also { entryBase = it }
        val d = com.xern.xrncal.domain.NaturalEntry.applyEntry(from, e, minutes)
        setTimes(d.copy(rrule = e.repeat?.toRepeat(d.startDate)?.let { RepeatRules.rule(it, d.startDate, d.allDay, d.zone) } ?: from.rrule))
        entry = e
    }

    private fun setTimes(d: EventDraft) {
        allDay = d.allDay
        startDate = d.startDate
        startTime = d.startTime
        endDate = d.endDate
        endTime = d.endTime
        rule = d.rrule
    }

    /** A title suggestion picked: its title (keeping an emoji typed), and its calendar, kind, length and place. */
    fun takeSuggestion(s: com.xern.xrncal.domain.TitleSuggestion, calendars: List<String>) {
        title = com.xern.xrncal.domain.TitleEmoji.fromSuggestion(title, s.title)
        entry = null
        entryBase = null
        if (s.calendarId in calendars) calendarId = s.calendarId
        if (location.isBlank()) s.location?.let { location = it }
        allDay = s.allDay
        if (s.allDay) {
            if (endDate.isBefore(startDate)) endDate = startDate
        } else {
            val start = java.time.LocalDateTime.of(startDate, startTime)
            val end = start.plusMinutes((s.durationMinutes ?: java.time.Duration.between(start, java.time.LocalDateTime.of(endDate, endTime)).toMinutes().takeIf { it > 0 }?.toInt() ?: 60).toLong())
            endDate = end.toLocalDate()
            endTime = end.toLocalTime()
        }
    }

    /** Whether anything differs from what the editor opened with. */
    fun changed(): Boolean = calendarId != initialCalendar || draft() != initial.copy(rrule = initial.rrule?.let { RepeatRules.fitUntil(it, initial.allDay, initial.zone) })

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

/** The editor's fields can't be changed: a read-only calendar's event, or a holiday. */
val LocalLocked = compositionLocalOf { false }

/**
 * The editor, opened straight from an event as One Calendar and the laptop do (docs/spec.md, phase
 * 2 item 8): the bar in the event's colour with ✕, "Add" or "Edit", ⋮ (Copy, Share), Delete and
 * ✓; then Subject, Calendar with the event's colour, All day, Start and End (date, time, time
 * zone; the lunar date under the start), Repeat, Location, Reminder and Description. An event
 * that couldn't be sent says why at the top, with Keep mine and Keep theirs. A [locked] event (a
 * read-only calendar's, a holiday) shows its fields without ✓ or Delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    adding: Boolean,
    calendars: List<CalendarInfo>,
    look: Look,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    locked: Boolean = false,
    syncProblem: String? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onKeepMine: () -> Unit = {},
    onKeepTheirs: () -> Unit = {},
    aids: EditorAids = EditorAids(),
) {
    val ink = LocalInk.current
    val cal = calendars.firstOrNull { it.id == state.calendarId }
    var emojiOpen by remember { mutableStateOf(false) }
    var titleFocus by remember { mutableStateOf(false) }
    var placeFocus by remember { mutableStateOf(false) }
    val bar = Color(state.color ?: cal?.color ?: 0xFF308FFE.toInt())
    val fg = onColor(bar)
    val problem = state.draft().problem()
    var picking by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(ink.screen).imePadding()) {
        Row(Modifier.fillMaxWidth().background(bar).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.Filled.Close, onCancel, fg, "Cancel")
            Text(if (adding) "Add" else "Edit", style = type(20, FontWeight.SemiBold, fg), modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (onCopy != null || onShare != null) {
                Box {
                    BarIcon(Icons.Filled.MoreVert, { menu = true }, fg, "More")
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onCopy != null) DropdownMenuItem(text = { Text("Copy", style = type(15)) }, onClick = {
                            menu = false
                            onCopy()
                        })
                        if (onShare != null) DropdownMenuItem(text = { Text("Share", style = type(15)) }, onClick = {
                            menu = false
                            onShare()
                        })
                    }
                }
            }
            if (!locked && onDelete != null) BarIcon(Icons.Outlined.Delete, onDelete, fg, "Delete")
            if (!locked) BarIcon(Icons.Filled.Check, { if (problem == null) onSave() }, if (problem == null) fg else fg.copy(alpha = 0.4f), "Save")
        }
        CompositionLocalProvider(LocalLocked provides locked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).navigationBarsPadding()) {
                if (syncProblem != null) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(8.dp)).background(ink.field).padding(12.dp)) {
                        Text(problemLabel(syncProblem), style = type(15, FontWeight.SemiBold, ink.warn))
                        Row {
                            TextButton(onClick = onKeepMine) { Text("Keep mine", style = type(14, FontWeight.Medium, ink.accent)) }
                            TextButton(onClick = onKeepTheirs) { Text("Keep theirs", style = type(14, FontWeight.Medium, ink.accent)) }
                        }
                    }
                }
                Label("Subject")
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (aids.emoji) {
                        EmojiButton { emojiOpen = true }
                        Spacer(Modifier.width(10.dp))
                    }
                    Field(state.title, { state.typeTitle(it, if (adding) aids.entry else null, aids.eventMinutes) }, Modifier.weight(1f), onFocus = { titleFocus = it })
                }
                // "Saved as …": the title without the date and time typed into it (the laptop's).
                state.entry?.let { e -> Text("Saved as \u201c${e.title}\u201d", style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 4.dp)) }
                if (adding && titleFocus && !locked) {
                    val found = remember(state.title, state.startDate, state.startTime, state.allDay) { aids.titles(state.title) }
                    SuggestionRows(found.map { it.title to calendars.firstOrNull { c -> c.id == it.calendarId }?.color }) { i ->
                        state.takeSuggestion(found[i], calendars.map { it.id })
                    }
                }
                Label("Calendar")
                var colorOpen by remember { mutableStateOf(false) }
                Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CalendarPicker(calendars, state.calendarId) { state.calendarId = it } }
                    Row(
                        Modifier.height(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable(enabled = !locked) { colorOpen = true }
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
                    aids.reminderDefaults?.let { d -> if (state.reminders.orEmpty() == d.forNew(state.allDay)) state.reminders = d.forNew(on) }
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
                if (look.lunar) Text("Âm lịch " + Format.lunarLong(state.startDate), style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                Label("End")
                WhenRow(state.endDate, state.endTime, state.allDay, look, { picking = "endDate" }, { picking = "endTime" }, { picking = "zone" })
                if (problem != null) Text(problem, style = type(13, color = ink.warn), modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                if (!state.allDay && state.zone != look.zone) Text(state.zone.id, style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                Divider()
                SwitchRow("Repeat", state.rule != null) { on ->
                    state.rule = if (on) RepeatRules.rule(com.xern.xrncal.domain.Repeat(Frequency.WEEKLY, weekdays = setOf(state.startDate.dayOfWeek)), state.startDate, state.allDay, state.zone) else null
                }
                RepeatForm(state) { picking = "until" }
                Divider()
                Label("Location")
                Field(state.location, { state.location = it }, Modifier.padding(horizontal = 16.dp), onFocus = { placeFocus = it })
                if (placeFocus && !locked && state.location.isNotBlank()) {
                    val places = remember(state.location) { aids.places(state.location) }
                    SuggestionRows(places.map { it.location to null }) { i -> state.location = places[i].location }
                }
                Label("Reminder")
                ReminderSection(state)
                Label("Description")
                Field(state.notes, { state.notes = it }, Modifier.padding(horizontal = 16.dp).heightIn(min = 96.dp), singleLine = false)
            }
        }
    }
    when (picking) {
        "startDate" -> DatePick(state.startDate, { picking = null }) { state.moveStart(it, state.startTime) }
        "endDate" -> DatePick(state.endDate, { picking = null }) { state.endDate = it }
        "startTime" -> TimeListPick(state.startTime, aids.timeStep, look.h24, { picking = null }) { state.moveStart(state.startDate, it) }
        "endTime" -> TimeListPick(state.endTime, aids.timeStep, look.h24, { picking = null }) { state.endTime = it }
        "zone" -> ZonePick(state.zone, aids.preferredZones, { picking = null }) { state.zone = it }
        "until" -> {
            val r = state.rule?.let { RepeatRules.parse(it, state.startDate) }
            DatePick(r?.end?.until ?: state.startDate, { picking = null }) { u ->
                if (r != null) state.rule = RepeatRules.rule(r.copy(end = RepeatEnd(until = u)), state.startDate, state.allDay, state.zone)
            }
        }
    }
    if (emojiOpen) {
        val lead = com.xern.xrncal.domain.TitleEmoji.leading(state.title)
        EmojiPanel(lead?.emoji, aids.emojiFavorites, onPick = { e ->
            state.title = com.xern.xrncal.domain.TitleEmoji.withLeading(state.title, e)
            aids.onEmoji(e)
            emojiOpen = false
        }, onRemove = {
            state.title = com.xern.xrncal.domain.TitleEmoji.withoutLeading(state.title).trimStart()
            emojiOpen = false
        }, onDismiss = { emojiOpen = false })
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = type(13, color = LocalInk.current.muted), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = LocalInk.current.line, modifier = Modifier.padding(top = 14.dp))

@Composable
fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, number: Boolean = false, password: Boolean = false, onFocus: (Boolean) -> Unit = {}) {
    val ink = LocalInk.current
    TextField(
        value = value,
        onValueChange = onChange,
        readOnly = LocalLocked.current,
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
        modifier = modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
    )
}

@Composable
private fun Picker(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val ink = LocalInk.current
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable(enabled = !LocalLocked.current, onClick = onClick).padding(start = 12.dp, end = 4.dp),
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
    val locked = LocalLocked.current
    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { onChange(!on) }.padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = type(17, color = ink.text), modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, enabled = !locked, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent))
    }
}

@Composable
private fun WhenRow(date: LocalDate, time: LocalTime, allDay: Boolean, look: Look, onDate: () -> Unit, onTime: () -> Unit, onZone: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Picker(Format.dateButton(date), onDate, Modifier.weight(1.4f))
        if (!allDay) {
            Picker(Format.time(time, look.h24), onTime, Modifier.weight(0.9f))
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field).clickable(enabled = !LocalLocked.current, onClick = onZone), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Language, contentDescription = "Time zone", tint = ink.muted)
            }
        }
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
                if (!LocalLocked.current) IconButton(onClick = { state.reminders = list - r }) { Icon(Icons.Filled.Close, contentDescription = "Remove reminder", tint = ink.muted) }
            }
        }
        Box {
            if (!LocalLocked.current) Text("+ Reminder", style = type(15, FontWeight.Medium, ink.accent), modifier = Modifier.clickable { adding = true }.padding(vertical = 8.dp, horizontal = 4.dp))
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

/** Every zone the phone knows, found by any part of its name; the preferred ones and the phone's first. */
@Composable
fun ZonePick(current: ZoneId, preferred: List<String>, onDismiss: () -> Unit, onPick: (ZoneId) -> Unit) {
    val ink = LocalInk.current
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() + "UTC" }
    val ordered = remember(preferred, current) { zoneChoices(preferred, current, all) }
    val shown = ordered.filter { it.replace('_', ' ').contains(query.trim(), ignoreCase = true) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.sheet).padding(vertical = 12.dp)) {
            Field(query, { query = it }, Modifier.padding(horizontal = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(shown) { id ->
                    Row(Modifier.fillMaxWidth().clickable {
                        onPick(ZoneId.of(id))
                        onDismiss()
                    }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(id.replace('_', ' '), style = type(15, if (id == current.id) FontWeight.SemiBold else FontWeight.Normal, ink.text), modifier = Modifier.weight(1f))
                        Text(offsetLabel(id), style = type(13, color = ink.muted))
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
