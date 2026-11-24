package com.xern.xrncal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.xern.xrncal.domain.IcsFiles
import com.xern.xrncal.domain.InsertRequest
import com.xern.xrncal.domain.Reminders
import com.xern.xrncal.domain.ical.ICalParser
import com.xern.xrncal.ui.Handed
import com.xern.xrncal.ui.HandedScreen
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What other apps hand the calendar (docs/spec.md, "Other apps"): "add to calendar" (Android's
 * insert intent for CalendarContract.Events) opens the editor on a new event filled in from the
 * request, saved only on ✓; an .ics file opens its one event the same way, or an import of its
 * several. It runs in the asking app's task, so ✓ or ✕ goes back there.
 */
class AddEventActivity : ComponentActivity() {
    private val graph get() = (application as XrnCalApp).graph
    private var handed by mutableStateOf<Handed>(Handed.Reading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handed = handedBy(intent)
        setThemedContent(graph) { HandedScreen(graph, handed, onDone = ::finish) }
    }

    private val defaults get() = graph.settings.value.let { Reminders.Defaults(it.timedReminder, it.allDayReminder) }

    private fun handedBy(intent: Intent): Handed {
        val s = graph.settings.value
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return Handed.Problem(CANT_OPEN)
                lifecycleScope.launch { handed = read(uri) }
                Handed.Reading
            }
            else -> Handed.Event(InsertRequest.draft(extrasOf(intent), Instant.now(), s.zoneId(), s.eventMinutes, defaults))
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

    /**
     * An .ics file, read through the content resolver: the app that hands it over grants the read,
     * so no storage permission is needed. One event goes to the editor, several to the import.
     */
    private suspend fun read(uri: Uri): Handed {
        val bytes = withContext(Dispatchers.IO) { open(uri) } ?: return Handed.Problem(CANT_OPEN)
        // Past this it isn't a calendar a phone would hand over (ICalParser's own limit).
        if (bytes.size > ICalParser.MAX_CHARS) return Handed.Problem(IcsFiles.NOT_CALENDAR)
        val s = graph.settings.value
        return when (val read = withContext(Dispatchers.Default) { IcsFiles.read(bytes.toString(Charsets.UTF_8), s.zoneId(), defaults) }) {
            is IcsFiles.Read.One -> Handed.Event(read.draft)
            is IcsFiles.Read.Many -> Handed.Events(read)
            is IcsFiles.Read.Unreadable -> Handed.Problem(read.line)
        }
    }

    /** The file's bytes, stopping a little past ICalParser's limit; null when it can't be opened. */
    private fun open(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= ICalParser.MAX_CHARS) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val CANT_OPEN = "Can't open this file"
    }
}
