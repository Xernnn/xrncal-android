package com.xern.xrncal

import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.MemoryRungLog
import com.xern.xrncal.data.ReminderPlanner
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.SettingsMigration
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.EventReminder
import com.xern.xrncal.domain.Reminders
import com.xern.xrncal.ui.newDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one alarm on the soonest reminder, and what rings when it goes off (docs/spec.md, "Reminders"). */
class ReminderPlanTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private var now = Instant.parse("2026-11-24T01:00:00Z") // 08:00 in Ho Chi Minh
    private var settings = Settings()
    private val store = MemoryCalendarStore()
    private val events = EventRepository(store, { settings }, { zone }, { now })
    private var alarm: Long? = null
    private val scheduler = object : AlarmScheduler {
        override fun set(at: Long) {
            alarm = at
        }
        override fun cancel() {
            alarm = null
        }
    }
    private val planner = ReminderPlanner(events, { settings }, MemoryRungLog(), scheduler, { zone }, { now })

    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun event(title: String, day: String, from: String, to: String, reminders: List<EventReminder>?, allDay: Boolean = false) = runBlocking {
        store.putCalendar(CalendarEntity("cal", CalendarEntity.LOCAL, "Phone", 0))
        events.create("cal", EventDraft(title, allDay, LocalDate.parse(day), LocalTime.parse(from), LocalDate.parse(day), LocalTime.parse(to), zone, reminders = reminders))
    }

    @Test
    fun setsTheAlarmForTheSoonestReminder() = runBlocking {
        event("Dentist", "2026-11-24", "10:00", "11:00", listOf(EventReminder(30)))
        event("Standup", "2026-11-24", "09:00", "09:15", listOf(EventReminder(5), EventReminder(15)))
        assertEquals(at("2026-11-24T08:45").toEpochMilli(), planner.rearm())
        assertEquals(alarm, at("2026-11-24T08:45").toEpochMilli())
    }

    @Test
    fun anEventWithoutAlarmsNeverRingsWhateverTheDefaults() = runBlocking {
        settings = settings.copy(timedReminder = 10, allDayReminder = 900)
        event("No alarm", "2026-11-24", "12:00", "13:00", null)
        event("Removed", "2026-11-24", "11:00", "12:00", emptyList())
        event("All day", "2026-11-25", "00:00", "00:00", null, allDay = true)
        assertNull(planner.rearm())
        now = at("2026-11-24T11:50")
        assertEquals(emptyList<Any>(), planner.ringing())
    }

    @Test
    fun theAlarm010SetForAnEventWithoutAlarmsIsCancelledAfterTheUpdate() = runBlocking {
        // 0.1.0 rang its 10-minute default for an event with no VALARM and set the phone's alarm for it.
        event("No alarm", "2026-11-24", "12:00", "13:00", null)
        alarm = at("2026-11-24T11:50").toEpochMilli()
        // The update broadcast (and the app's start, and every sync) works the alarm out again.
        assertNull(planner.rearm())
        assertNull(alarm)
    }

    @Test
    fun aNewEventIsSavedWithTheDefaultAsItsOwnAlarm() = runBlocking {
        store.putCalendar(CalendarEntity("cal", CalendarEntity.LOCAL, "Phone", 0))
        val defaults = Reminders.Defaults(settings.timedReminder, settings.allDayReminder)
        val draft = newDraft(LocalDate.parse("2026-11-24"), 12, now, zone, 60, defaults.forNew(false)).copy(title = "Lunch")
        val id = events.create("cal", draft)
        assertTrue(store.resource(id)!!.ics.contains("TRIGGER:-PT15M"))
        assertEquals(at("2026-11-24T11:45").toEpochMilli(), planner.rearm())
        // Changing the default later changes nothing for it: the alarm is its own.
        settings = settings.copy(timedReminder = null)
        assertEquals(at("2026-11-24T11:45").toEpochMilli(), planner.rearm())
    }

    @Test
    fun remindersStillAtTheOldDefaultsMoveToOneCalendarsOnUpdate() {
        assertEquals(15 to 720, SettingsMigration.reminders(1, 10, null))
        assertEquals(30 to 900, SettingsMigration.reminders(1, 30, 900))
        assertEquals(null to 720, SettingsMigration.reminders(1, null, null))
        assertEquals(10 to null, SettingsMigration.reminders(2, 10, null))
        assertEquals(Settings().timedReminder to Settings().allDayReminder, 15 to 720)
    }

    @Test
    fun ringsEachOnceAndThenTheNext() = runBlocking {
        event("Dentist", "2026-11-24", "10:00", "11:00", listOf(EventReminder(30), EventReminder(10)))
        now = at("2026-11-24T09:30")
        val first = planner.ringing()
        assertEquals(listOf("Dentist" to 30), first.map { it.occurrence.title to it.minutesBefore })
        assertEquals(emptyList<Any>(), planner.ringing())
        assertEquals(at("2026-11-24T09:50").toEpochMilli(), planner.rearm())
        now = at("2026-11-24T09:50")
        assertEquals(listOf(10), planner.ringing().map { it.minutesBefore })
        assertNull(planner.rearm())
    }

    @Test
    fun ofSeveralOverdueOnlyTheLatestRings() = runBlocking {
        event("Flight", "2026-11-24", "12:00", "14:00", listOf(EventReminder(1440), EventReminder(120), EventReminder(60)))
        now = at("2026-11-24T10:30") // the phone was off through 10:00
        assertEquals(now.toEpochMilli(), planner.rearm())
        assertEquals(listOf(120), planner.ringing().map { it.minutesBefore })
        assertEquals(at("2026-11-24T11:00").toEpochMilli(), planner.rearm())
    }

    @Test
    fun anAllDayReminderCountsBackFromMidnight() = runBlocking {
        event("Giỗ", "2026-11-26", "00:00", "00:00", listOf(EventReminder(900)), allDay = true)
        // The day before at 09:00.
        assertEquals(at("2026-11-25T09:00").toEpochMilli(), planner.rearm())
    }

    @Test
    fun holidaysNeverRing() = runBlocking {
        settings = settings.copy(allDayReminder = 0, holidays = setOf("holidays:international"))
        now = at("2026-12-24T08:00")
        assertNull(planner.rearm())
    }

    @Test
    fun aSnoozedReminderRingsAgainAfterItsMinutes() = runBlocking {
        event("Dentist", "2026-11-24", "10:00", "11:00", listOf(EventReminder(30)))
        now = at("2026-11-24T09:30")
        val ring = planner.ringing().single()
        // Snooze, as the notification's action does: ten minutes on, the alarm is set for it.
        assertEquals(at("2026-11-24T09:40").toEpochMilli(), planner.snooze(ring.occurrence.resourceId, ring.occurrence.start.toEpochMilli(), 30, 10))
        assertEquals(alarm, at("2026-11-24T09:40").toEpochMilli())
        now = at("2026-11-24T09:35")
        assertEquals(emptyList<Any>(), planner.ringing())
        now = at("2026-11-24T09:40")
        assertEquals(listOf("Dentist" to 30), planner.ringing().map { it.occurrence.title to it.minutesBefore })
        // Rung once more, and then nothing until something else is due.
        assertEquals(emptyList<Any>(), planner.ringing())
        assertNull(planner.rearm())
    }
}
