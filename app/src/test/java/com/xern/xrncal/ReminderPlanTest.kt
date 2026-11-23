package com.xern.xrncal

import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.MemoryRungLog
import com.xern.xrncal.data.ReminderPlanner
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.EventReminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun anEventWithoutAlarmsTakesTheDefaultAndNoneMeansNone() = runBlocking {
        event("Default", "2026-11-24", "12:00", "13:00", null)
        event("Silent", "2026-11-24", "11:00", "12:00", emptyList())
        assertEquals(at("2026-11-24T11:50").toEpochMilli(), planner.rearm())
        settings = settings.copy(timedReminder = null)
        assertNull(planner.rearm())
        assertNull(alarm)
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
}
