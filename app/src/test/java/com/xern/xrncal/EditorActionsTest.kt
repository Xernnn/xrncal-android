package com.xern.xrncal

import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.Scope
import com.xern.xrncal.ui.EditorState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor opened from an event: saving only what changed, and Undo after Delete (docs/spec.md, phase 2 item 8). */
class EditorActionsTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-11-24T01:00:00Z")
    private val store = MemoryCalendarStore()
    private val events = EventRepository(store, { Settings() }, { zone }, { now })

    private fun draft(title: String, rule: String? = null) =
        EventDraft(title, false, LocalDate.parse("2026-11-24"), LocalTime.parse("09:00"), LocalDate.parse("2026-11-24"), LocalTime.parse("10:00"), zone, rrule = rule)

    private fun day(d: String) = runBlocking { events.occurrences(LocalDate.parse(d), LocalDate.parse(d)) }

    private fun calendar() = runBlocking { store.putCalendar(CalendarEntity("cal", CalendarEntity.LOCAL, "Phone", 0)) }

    @Test
    fun anEventOpenedAndLeftAsItWasHasNothingToSave() = runBlocking {
        calendar()
        events.create("cal", draft("Standup", "FREQ=WEEKLY;UNTIL=20261231"))
        val o = day("2026-12-01").single()
        val state = EditorState(events.draft(o, Scope.THIS)!!, o.calendarId)
        assertFalse(state.changed())
        state.title = "Standup (long)"
        assertTrue(state.changed())
        state.title = "Standup"
        state.calendarId = "other"
        assertTrue(state.changed())
    }

    @Test
    fun undoPutsADeletedEventBack() = runBlocking {
        calendar()
        events.create("cal", draft("Dentist"))
        val u = events.delete(day("2026-11-24").single(), Scope.ALL)!!
        assertTrue(day("2026-11-24").isEmpty())
        events.undo(u)
        assertEquals(listOf("Dentist"), day("2026-11-24").map { it.title })
    }

    @Test
    fun undoPutsBackOneDateTakenOutOfASeries() = runBlocking {
        calendar()
        events.create("cal", draft("Standup", "FREQ=DAILY;COUNT=3"))
        val u = events.delete(day("2026-11-25").single(), Scope.THIS)!!
        assertTrue(day("2026-11-25").isEmpty())
        events.undo(u)
        assertEquals(listOf("Standup"), day("2026-11-25").map { it.title })
        assertEquals(1, store.everything().size)
    }
}
