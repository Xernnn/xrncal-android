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

    @Test
    fun aNewEventsTitleFillsItsDayTimeAndRepeatAndIsSavedWithoutThem() {
        val ctx = com.xern.xrncal.domain.EntryContext(LocalDate.parse("2026-11-24"))
        val state = EditorState(com.xern.xrncal.ui.newDraft(LocalDate.parse("2026-11-24"), 12, now, zone, 60), "cal")
        state.typeTitle("Lunch with Sam tomorrow at 12:30", ctx, 60)
        assertEquals(LocalDate.parse("2026-11-25") to LocalTime.parse("12:30"), state.startDate to state.startTime)
        assertEquals(LocalTime.parse("13:30"), state.endTime)
        assertEquals("Lunch with Sam", state.draft().title)
        // Typed away again: the fields go back to what they were.
        state.typeTitle("Lunch with Sam", ctx, 60)
        assertEquals(LocalDate.parse("2026-11-24") to LocalTime.parse("12:00"), state.startDate to state.startTime)
        assertEquals(null, state.entry)
        state.typeTitle("Chạy bộ 6h sáng hàng ngày", ctx, 60)
        assertEquals("FREQ=DAILY", state.rule)
        assertEquals(LocalTime.parse("06:00"), state.startTime)
        assertEquals("Chạy bộ", state.draft().title)
        // An event being edited reads nothing into its title.
        val edit = EditorState(com.xern.xrncal.ui.newDraft(LocalDate.parse("2026-11-24"), 12, now, zone, 60), "cal")
        edit.typeTitle("Lunch tomorrow at 12:30", null, 60)
        assertEquals(LocalDate.parse("2026-11-24"), edit.startDate)
        assertEquals("Lunch tomorrow at 12:30", edit.draft().title)
    }

    @Test
    fun theSuggestionsLearnFromTheEventsAlreadyThere() = runBlocking {
        calendar()
        for (d in listOf("2026-11-10", "2026-11-17")) {
            events.create("cal", EventDraft("Dentist", false, LocalDate.parse(d), LocalTime.parse("16:00"), LocalDate.parse(d), LocalTime.parse("17:00"), zone, location = "12 Made-up Street"))
        }
        events.create("cal", draft("Design review"))
        val h = events.history()
        val titles = com.xern.xrncal.domain.TitleSuggestions.rank(h.titles, "de", java.time.Instant.parse("2026-11-24T09:00:00Z"), false, now, zone)
        assertEquals("Dentist", titles.first().title)
        assertEquals(60, titles.first().durationMinutes)
        assertEquals("12 Made-up Street", titles.first().location)
        assertEquals(listOf("12 Made-up Street"), com.xern.xrncal.domain.LocationSuggestions.rank(h.places, "made", now).map { it.location })
        // Picking one fills the calendar's event in.
        val state = EditorState(com.xern.xrncal.ui.newDraft(LocalDate.parse("2026-11-24"), 9, now, zone, 30), "other")
        state.title = "de"
        state.takeSuggestion(titles.first(), listOf("cal", "other"))
        assertEquals("Dentist", state.title)
        assertEquals("cal", state.calendarId)
        assertEquals("12 Made-up Street", state.location)
        assertEquals(LocalTime.parse("10:00"), state.endTime)
    }
}
