package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xern.xrncal.Graph
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.IcsFiles
import com.xern.xrncal.domain.Reminders
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch

/** What another app handed the calendar (docs/spec.md, "Other apps"). */
sealed interface Handed {
    /** A new event for the editor: "add to calendar", or an .ics file with one event. */
    data class Event(val draft: EventDraft) : Handed

    /** An .ics file with several events, to import. */
    data class Events(val file: IcsFiles.Read.Many) : Handed

    /** One short line: what couldn't be read. */
    data class Problem(val line: String) : Handed

    /** A file still being read. */
    data object Reading : Handed
}

/** The screen for what another app handed over, in the app's theme; [onDone] goes back to that app. */
@Composable
fun HandedScreen(graph: Graph, handed: Handed, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsState()
    val revision by graph.store.revision.collectAsState()
    val now = rememberNow()
    val today = now.atZone(settings.zoneId()).toLocalDate()
    val calendars by produceState<List<CalendarInfo>?>(null, revision, settings.holidays) {
        graph.events.localCalendar()
        value = graph.events.calendars()
    }
    LocalBackdrop.current()
    val cals = calendars
    if (cals != null) {
        val look = lookOf(context, settings, cals, today)
        when (handed) {
            is Handed.Event -> NewEventEditor(graph, settings, handed.draft, writableOf(cals), look, now, onDone)
            is Handed.Events -> Import(graph, settings, handed.file, writableOf(cals), onDone)
            is Handed.Problem -> HandedFrame("Import", onDone) {
                Text(handed.line, style = type(16, color = LocalInk.current.warn), modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp))
            }
            Handed.Reading -> Unit
        }
    }
}

/** The editor on a new event, in Options' default calendar; ✓ saves it, ✕ leaves it. */
@Composable
private fun NewEventEditor(graph: Graph, settings: Settings, draft: EventDraft, writable: List<CalendarInfo>, look: Look, now: Instant, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val calendar = remember { defaultCalendarOf(writable, settings.defaultCalendar) } ?: return
    val state = remember { EditorState(draft, calendar) }
    val history by produceState<EventRepository.History?>(null) { value = graph.events.history() }
    var saving by remember { mutableStateOf(false) }
    // The times came with the request: a title typed over isn't read for a date.
    val aids = editorAids(graph, settings, state, history, now, look.zone, entry = null, reminderDefaults = Reminders.Defaults(settings.timedReminder, settings.allDayReminder))
    EditorScreen(
        state, true, writable, look,
        onCancel = onDone,
        onSave = {
            if (!saving) {
                saving = true
                scope.launch {
                    graph.events.create(state.calendarId, state.draft())
                    onDone()
                }
            }
        },
        aids = aids,
    )
}

/** A file's events imported into the calendar picked (Options' default first), once. */
@Composable
private fun Import(graph: Graph, settings: Settings, file: IcsFiles.Read.Many, writable: List<CalendarInfo>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var calendarId by remember { mutableStateOf(defaultCalendarOf(writable, settings.defaultCalendar)) }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<EventRepository.ImportResult?>(null) }
    ImportScreen(
        file.events.size, file.first, file.last, writable, calendarId, { calendarId = it }, importing, result,
        onImport = {
            val id = calendarId
            if (id != null && !importing) {
                importing = true
                scope.launch {
                    result = graph.events.import(id, file.events)
                    importing = false
                }
            }
        },
        onClose = onDone,
    )
}

/**
 * Several events from an .ics file: how many, the dates they span, the calendar they go into, and
 * Import; then how many were added and how many the calendar held already.
 */
@Composable
fun ImportScreen(
    count: Int,
    first: LocalDate,
    last: LocalDate,
    calendars: List<CalendarInfo>,
    calendarId: String?,
    onCalendar: (String) -> Unit,
    importing: Boolean,
    result: EventRepository.ImportResult?,
    onImport: () -> Unit,
    onClose: () -> Unit,
) = HandedFrame("Import", onClose) {
    val ink = LocalInk.current
    Figure("Events", "$count")
    Figure("Dates", Format.dates(first, last))
    if (result == null) {
        Text("Calendar", style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
        CalendarPicker(calendars, calendarId.orEmpty(), onCalendar)
        WideButton("Import", enabled = calendarId != null && !importing, onClick = onImport)
    } else {
        Figure("Added", "${result.added}")
        Figure("Already there", "${result.alreadyThere}")
        WideButton("Done", enabled = true, onClick = onClose)
    }
}

/** A full screen opened by another app: the accent bar with ✕ and [title], then [content]. */
@Composable
private fun HandedFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.screen)) {
        Row(Modifier.fillMaxWidth().background(ink.accent).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.Filled.Close, onClose, Color.White, "Close")
            Text(title, style = type(20, FontWeight.Medium, Color.White), modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 8.dp, bottom = 24.dp), content = content)
    }
}

/** A short label and its figure, on one row. */
@Composable
private fun Figure(label: String, value: String) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
        Text(value, style = type(16, FontWeight.SemiBold, ink.text))
    }
}

@Composable
private fun WideButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ink.accent, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp).height(48.dp),
    ) { Text(text, style = type(16, FontWeight.Medium, Color.White)) }
}
