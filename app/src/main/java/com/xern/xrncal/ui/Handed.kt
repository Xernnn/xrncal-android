package com.xern.xrncal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xern.xrncal.Graph
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.Reminders
import java.time.Instant
import kotlinx.coroutines.launch

/** What another app handed the calendar (docs/spec.md, "Other apps"). */
sealed interface Handed {
    /** A new event for the editor: "add to calendar". */
    data class Event(val draft: EventDraft) : Handed
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
