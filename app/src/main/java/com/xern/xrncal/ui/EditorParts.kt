package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xern.xrncal.domain.EmojiGroupId
import com.xern.xrncal.domain.EntryContext
import com.xern.xrncal.domain.Frequency
import com.xern.xrncal.domain.LocationSuggestion
import com.xern.xrncal.domain.Repeat
import com.xern.xrncal.domain.RepeatEnd
import com.xern.xrncal.domain.RepeatRules
import com.xern.xrncal.domain.TitleEmoji
import com.xern.xrncal.domain.TitleSuggestion
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the editor offers beyond its fields (the laptop's extras, docs/spec.md phase 2 item 9): the
 * emoji panel and its favourites, title and place suggestions, the typed-date reading for a new
 * event ([entry]), the zones to list first and the time list's step.
 */
data class EditorAids(
    val emoji: Boolean = false,
    val emojiFavorites: List<String> = emptyList(),
    val onEmoji: (String) -> Unit = {},
    val titles: (String) -> List<TitleSuggestion> = { emptyList() },
    val places: (String) -> List<LocationSuggestion> = { emptyList() },
    val entry: EntryContext? = null,
    val preferredZones: List<String> = emptyList(),
    val timeStep: Int = 15,
    val eventMinutes: Int = 60,
    /** A new event's reminder defaults: switching All day swaps one for the other while it is untouched. */
    val reminderDefaults: com.xern.xrncal.domain.Reminders.Defaults? = null,
)

/** The emoji button in front of the subject, as One Calendar's and the laptop's. */
@Composable
fun EmojiButton(onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field)
            .clickable(enabled = !LocalLocked.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Emoji" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null, tint = ink.muted) }
}

private val GROUP_NAMES = mapOf(
    EmojiGroupId.PEOPLE to "People", EmojiGroupId.ACTIVITIES to "Activities and sport", EmojiGroupId.FOOD to "Food and drink",
    EmojiGroupId.TRAVEL to "Travel and places", EmojiGroupId.OBJECTS to "Objects", EmojiGroupId.SYMBOLS to "Symbols", EmojiGroupId.NATURE to "Nature",
)

/**
 * The laptop's emoji panel: "Remove" when the title starts with one, the ones picked most, then
 * the built-in set by kind. A pick replaces the emoji the title starts with.
 */
@Composable
fun EmojiPanel(current: String?, favorites: List<String>, onPick: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val ink = LocalInk.current
    Dialog(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).background(ink.sheet).padding(12.dp)) {
            if (current != null) {
                item {
                    Text("Remove $current", style = type(15, FontWeight.Medium, ink.accent), modifier = Modifier.clickable(onClick = onRemove).padding(8.dp))
                }
            }
            val sections = (if (favorites.isNotEmpty()) listOf("Used most" to favorites) else emptyList()) +
                TitleEmoji.GROUPS.map { (GROUP_NAMES[it.id] ?: it.id.name) to it.emojis }
            for ((name, emojis) in sections) {
                item(key = name) { Text(name, style = type(12, FontWeight.Medium, ink.muted), modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)) }
                items(emojis.chunked(8), key = { name + it.first() }) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        for (e in row) {
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp)).clickable { onPick(e) }.semantics { contentDescription = e },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, style = type(22)) }
                        }
                        repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Suggestions under a field as it is typed: a title's with its calendar's colour, or a place. */
@Composable
fun SuggestionRows(items: List<Pair<String, Int?>>, onPick: (Int) -> Unit) {
    val ink = LocalInk.current
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(4.dp)).background(ink.field)) {
        items.forEachIndexed { i, (text, color) ->
            Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    Swatch(androidx.compose.ui.graphics.Color(color), 10.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, style = type(15, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One Calendar's compact repeat form: "every [N] [days / weeks / months / years]", "on" the
 * weekdays (weeks) or the date or nth weekday (months), "ends [never / on date / after N times]".
 * A rule the form can't show is kept as it is and named.
 */
@Composable
fun RepeatForm(state: EditorState, onPickUntil: () -> Unit) {
    val ink = LocalInk.current
    val rule = state.rule ?: return
    val r = RepeatRules.parse(rule, state.startDate)
    if (r == null) {
        Text("Custom: " + RepeatRules.describe(rule, state.startDate), style = type(14, color = ink.muted), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        return
    }
    fun set(next: Repeat) {
        state.rule = RepeatRules.rule(next, state.startDate, state.allDay, state.zone)
    }
    FormRow("every") {
        Field(r.interval.toString(), { t -> t.filter(Char::isDigit).take(3).toIntOrNull()?.takeIf { it >= 1 }?.let { set(r.copy(interval = it)) } }, Modifier.width(64.dp), number = true)
        Spacer(Modifier.width(10.dp))
        Dropdown(unitName(r.frequency, r.interval), Frequency.entries.map { unitName(it, r.interval) }, Modifier.width(140.dp)) { i ->
            val f = Frequency.entries[i]
            set(r.copy(frequency = f, weekdays = if (f == Frequency.WEEKLY) setOf(state.startDate.dayOfWeek) else emptySet(), monthlyByWeekday = false))
        }
    }
    if (r.frequency == Frequency.WEEKLY) {
        FormRow("on") {
            for (d in DayOfWeek.entries) {
                val on = d in r.weekdays
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Format.weekdayLetter(d), style = type(14, color = ink.text))
                    Checkbox(
                        checked = on,
                        enabled = !LocalLocked.current,
                        onCheckedChange = {
                            val days = if (on) r.weekdays - d else r.weekdays + d
                            if (days.isNotEmpty()) set(r.copy(weekdays = days))
                        },
                        colors = CheckboxDefaults.colors(checkedColor = ink.accent),
                        modifier = Modifier.semantics { contentDescription = d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) },
                    )
                }
            }
        }
    }
    if (r.frequency == Frequency.MONTHLY) {
        val nth = (state.startDate.dayOfMonth - 1) / 7 + 1
        val ord = listOf("first", "second", "third", "fourth", "last").getOrElse(nth - 1) { "last" }
        val choices = listOf("day ${state.startDate.dayOfMonth}", "the $ord ${state.startDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}")
        FormRow("on") {
            Dropdown(choices[if (r.monthlyByWeekday) 1 else 0], choices, Modifier.weight(1f)) { i -> set(r.copy(monthlyByWeekday = i == 1)) }
        }
    }
    val ends = listOf("never", "on date", "after")
    val endIndex = when {
        r.end.count != null -> 2
        r.end.until != null -> 1
        else -> 0
    }
    FormRow("ends") {
        Dropdown(ends[endIndex], ends, Modifier.width(140.dp)) { i ->
            set(r.copy(end = when (i) {
                1 -> RepeatEnd(until = r.end.until ?: state.startDate.plusMonths(1))
                2 -> RepeatEnd(count = r.end.count ?: 10)
                else -> RepeatEnd()
            }))
        }
        Spacer(Modifier.width(10.dp))
        r.end.until?.let { u -> Dropdown(Format.dateButton(u), emptyList(), Modifier.weight(1f), onOpen = onPickUntil) {} }
        r.end.count?.let { count ->
            Field(count.toString(), { t -> t.filter(Char::isDigit).take(4).toIntOrNull()?.takeIf { it >= 1 }?.let { set(r.copy(end = RepeatEnd(count = it))) } }, Modifier.width(72.dp), number = true)
            Text("  times", style = type(16, color = ink.text))
        }
    }
}

private fun unitName(f: Frequency, interval: Int): String = when (f) {
    Frequency.DAILY -> "day"
    Frequency.WEEKLY -> "week"
    Frequency.MONTHLY -> "month"
    Frequency.YEARLY -> "year"
} + if (interval == 1) "" else "s"

@Composable
private fun FormRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type(16, color = ink.muted), modifier = Modifier.width(56.dp))
        content()
    }
}

/** A field-like button with a drop-down of [options]; with none, [onOpen] opens something else (a date). */
@Composable
fun Dropdown(text: String, options: List<String>, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, onPick: (Int) -> Unit) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(4.dp)).background(ink.field)
                .clickable(enabled = !LocalLocked.current) { if (onOpen != null) onOpen() else open = true }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = type(16, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ink.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o, style = type(15)) }, onClick = {
                    open = false
                    onPick(i)
                })
            }
        }
    }
}

/** The times of a day every [step] minutes (the laptop's time picker), with [current] among them, scrolled to it. */
@Composable
fun TimeListPick(current: LocalTime, step: Int, h24: Boolean, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val ink = LocalInk.current
    val times = remember(current, step) { (generateSequence(LocalTime.MIDNIGHT) { it.plusMinutes(step.toLong()).takeIf { t -> t != LocalTime.MIDNIGHT } }.toList() + current).distinct().sorted() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(maxOf(0, times.indexOf(current) - 3)) }
    Dialog(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).clip(RoundedCornerShape(12.dp)).background(ink.sheet), state = list) {
            items(times) { t ->
                Text(
                    Format.time(t, h24),
                    style = type(17, if (t == current) FontWeight.SemiBold else FontWeight.Normal, if (t == current) ink.accent else ink.text),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onPick(t)
                        onDismiss()
                    }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** The zone list: the preferred zones and the phone's first (the laptop's "preferred time zones"), then every other. */
fun zoneChoices(preferred: List<String>, current: ZoneId, all: List<String>): List<String> {
    val first = (preferred + ZoneId.systemDefault().id + current.id).filter { id -> runCatching { ZoneId.of(id) }.isSuccess }.distinct()
    return first + all.filter { it !in first }
}

/** "UTC+7", "UTC+5:30", "UTC" for a zone now. */
fun offsetLabel(id: String): String {
    val offset = ZoneId.of(id).rules.getOffset(Instant.now())
    return if (offset.totalSeconds == 0) "UTC" else "UTC$offset"
}
