package com.xern.xrncal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.xern.xrncal.domain.InsertRequest
import com.xern.xrncal.domain.Reminders
import com.xern.xrncal.ui.Handed
import com.xern.xrncal.ui.HandedScreen
import java.time.Instant

/**
 * What other apps hand the calendar (docs/spec.md, "Other apps"): "add to calendar" (Android's
 * insert intent for CalendarContract.Events) opens the editor on a new event filled in from the
 * request, saved only on ✓. It runs in the asking app's task, so ✓ or ✕ goes back there.
 */
class AddEventActivity : ComponentActivity() {
    private val graph get() = (application as XrnCalApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handed = handedBy(intent)
        setThemedContent(graph) { HandedScreen(graph, handed, onDone = ::finish) }
    }

    private fun handedBy(intent: Intent): Handed {
        val s = graph.settings.value
        return when (intent.action) {
            Intent.ACTION_INSERT, Intent.ACTION_EDIT ->
                Handed.Event(InsertRequest.draft(extrasOf(intent), Instant.now(), s.zoneId(), s.eventMinutes, Reminders.Defaults(s.timedReminder, s.allDayReminder)))
            else -> Handed.Event(InsertRequest.draft(emptyMap(), Instant.now(), s.zoneId(), s.eventMinutes, Reminders.Defaults(s.timedReminder, s.allDayReminder)))
        }
    }

    /**
     * The request's extras InsertRequest reads. Each is read on its own: an extra of a class this
     * app doesn't have can't be read, and leaves only itself out.
     */
    private fun extrasOf(intent: Intent): Map<String, Any?> {
        val b = intent.extras ?: return emptyMap()
        val keys = listOf(
            InsertRequest.TITLE, InsertRequest.DESCRIPTION, InsertRequest.LOCATION, InsertRequest.RRULE, InsertRequest.TIME_ZONE,
            InsertRequest.BEGIN, InsertRequest.END, InsertRequest.ALL_DAY, InsertRequest.EMAIL,
        )
        @Suppress("DEPRECATION")
        return keys.associateWith { k -> runCatching { b.get(k) }.getOrNull() }
    }
}
