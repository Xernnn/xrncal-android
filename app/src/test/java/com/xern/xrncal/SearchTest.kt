package com.xern.xrncal

import com.xern.xrncal.data.CalendarEntity
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.EventDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** One Calendar's search tabs: Future at each match's next date, Past at its last (docs/spec.md, phase 2 item 10). */
class SearchTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-11-24T01:00:00Z")
    private var settings = Settings()
    private val store = MemoryCalendarStore()
    private val events = EventRepository(store, { settings }, { zone }, { now })

    private suspend fun event(title: String, day: String, rule: String? = null) =
        events.create("cal", EventDraft(title, false, LocalDate.parse(day), LocalTime.parse("09:00"), LocalDate.parse(day), LocalTime.parse("10:00"), zone, rrule = rule))

    @Test
    fun futureListsTheNextDatesSoonestFirstAndPastTheLastOnesLatestFirst() = runBlocking {
        store.putCalendar(CalendarEntity("cal", CalendarEntity.LOCAL, "Phone", 0))
        event("Dentist check", "2026-11-10")
        event("Dentist", "2026-12-01")
        event("Dentist visit", "2026-11-26")
        event("Swim with the dentist", "2026-11-02", "FREQ=WEEKLY;COUNT=6")
        assertEquals(
            listOf("Dentist visit" to "2026-11-26", "Swim with the dentist" to "2026-11-30", "Dentist" to "2026-12-01"),
            events.search("dent").map { it.title to it.firstDay.toString() },
        )
        assertEquals(
            listOf("Swim with the dentist" to "2026-11-23", "Dentist check" to "2026-11-10"),
            events.search("dent", past = true).map { it.title to it.firstDay.toString() },
        )
        // As far back as Options' history.
        event("Dentist long ago", "2022-11-01")
        assertEquals(2, events.search("dent", past = true).size)
        settings = settings.copy(historyYears = 0)
        assertEquals(3, events.search("dent", past = true).size)
    }
}
