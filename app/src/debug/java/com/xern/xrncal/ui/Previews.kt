package com.xern.xrncal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xern.xrncal.SyncStatus
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.Problem
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.View
import com.xern.xrncal.domain.EventDraft
import com.xern.xrncal.domain.EventReminder
import com.xern.xrncal.domain.HolidayCalendar
import com.xern.xrncal.domain.Holidays
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Reminders
import com.xern.xrncal.domain.ical.ICalTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

// Made-up calendars and events for drawing the screens without a phone:
//     android preview render "?:app" --fqn=com.xern.xrncal.ui.PreviewsKt.<Name> -o <folder>
// Nothing here is anyone's real calendar.

private val ZONE = ZoneId.of("Asia/Ho_Chi_Minh")
private val TODAY = LocalDate.of(2026, 11, 24)
private val NOW = LocalDateTime.of(2026, 11, 24, 10, 40).atZone(ZONE).toInstant()

private val CALENDARS = listOf(
    CalendarInfo("work", "Work", 0xFF33B679.toInt(), false, true, EventRepository.SERVER_GROUP),
    CalendarInfo("home", "Home", 0xFF4285F4.toInt(), false, true, EventRepository.SERVER_GROUP),
    CalendarInfo("family", "Family", 0xFFF4511E.toInt(), false, true, EventRepository.SERVER_GROUP),
    CalendarInfo("study", "Study", 0xFF8E24AA.toInt(), false, false, EventRepository.SERVER_GROUP),
    CalendarInfo("phone", "Phone", 0xFF308FFE.toInt(), false, true, EventRepository.LOCAL_GROUP),
    CalendarInfo(HolidayCalendar.VIETNAM.id, HolidayCalendar.VIETNAM.title, HolidayCalendar.VIETNAM.color, true, true, EventRepository.HOLIDAY_GROUP),
    CalendarInfo(HolidayCalendar.INTERNATIONAL.id, HolidayCalendar.INTERNATIONAL.title, HolidayCalendar.INTERNATIONAL.color, true, false, EventRepository.HOLIDAY_GROUP),
)

private fun look(lunar: Boolean = true) = Look(CALENDARS.associate { it.id to it.color }, TODAY, h24 = true, lunar = lunar, weekNumbers = true, zone = ZONE)

private var n = 0

private fun timed(title: String, cal: String, day: String, from: String, to: String, location: String = "", recurring: Boolean = false): Occurrence {
    val s = LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(from)).atZone(ZONE)
    val e = LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(to)).let { if (it.toLocalTime() <= s.toLocalTime()) it.plusDays(1) else it }.atZone(ZONE)
    return Occurrence("r${n++}", cal, "u$n", if (recurring) ICalTime.zoned(s.toInstant(), ZONE) else null, title, location, "", false, s.toInstant(), e.toInstant(), s.toLocalDate(), e.minusNanos(1).toLocalDate(), recurring, false, null)
}

private fun allDay(title: String, cal: String, first: String, last: String = first): Occurrence {
    val f = LocalDate.parse(first)
    val l = LocalDate.parse(last)
    return Occurrence("r${n++}", cal, "u$n", null, title, "", "", true, f.atStartOfDay(ZONE).toInstant(), l.plusDays(1).atStartOfDay(ZONE).toInstant(), f, l, false, false, null)
}

private val EVENTS: List<Occurrence> by lazy {
    val weekly = (0L until 7L).flatMap { w ->
        val mon = LocalDate.of(2026, 10, 26).plusWeeks(w)
        listOf(
            timed("Status briefing", "work", mon.toString(), "09:00", "10:00", "Room 3.44", recurring = true),
            timed("Standup", "work", mon.plusDays(2).toString(), "09:30", "09:45", recurring = true),
            timed("Swimming", "home", mon.plusDays(3).toString(), "18:00", "19:00", "Made-up Pool", recurring = true),
        )
    }
    weekly + listOf(
        timed("Lunch with Sam", "home", "2026-11-25", "12:00", "13:00", "Cơm Tấm (made up)"),
        timed("Dentist", "home", "2026-11-26", "16:00", "17:00", "12 Made-up Street"),
        timed("Phở with Tuấn", "family", "2026-11-27", "18:30", "20:00"),
        timed("Design review", "work", "2026-11-24", "14:00", "15:30"),
        timed("Call the bank", "phone", "2026-11-24", "11:00", "11:20"),
        timed("Project sync", "work", "2026-11-24", "14:30", "15:00"),
        allDay("Trip to Huế", "family", "2026-11-28", "2026-11-30"),
        allDay("Giỗ bà (made up)", "family", "2026-12-03"),
        allDay("Conference", "work", "2026-12-08", "2026-12-10"),
        timed("Tennis", "home", "2026-12-05", "08:00", "09:30"),
        timed("Night train to Đà Nẵng", "family", "2026-12-11", "21:00", "07:00"),
        timed("Book club", "home", "2026-11-12", "19:00", "21:00"),
        timed("Haircut", "phone", "2026-11-14", "10:00", "10:30"),
        timed("Quarterly review", "work", "2026-11-18", "13:00", "15:00"),
        timed("Movie night", "family", "2026-11-21", "20:00", "22:00"),
    ) + Holidays.between(HolidayCalendar.VIETNAM, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 31)).map { h ->
        Occurrence("h${h.date}", HolidayCalendar.VIETNAM.id, "h${h.date}", null, h.title, "", h.notes, true, h.date.atStartOfDay(ZONE).toInstant(), h.date.plusDays(1).atStartOfDay(ZONE).toInstant(), h.date, h.date, false, false, emptyList(), h.color)
    }
}

private fun between(from: LocalDate, to: LocalDate) = EVENTS.filter { !it.firstDay.isAfter(to) && !it.lastDay.isBefore(from) }

/** The calendar's top bar, as App draws it. */
@Composable
private fun Bar(title: String) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().background(ink.bar).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Filled.Menu, {})
        Text(title, style = type(24, color = ink.text), modifier = Modifier.padding(start = 12.dp).weight(1f))
        BarIcon(Icons.Filled.Add, {})
        TodayButton(TODAY.dayOfMonth) {}
    }
}

@Composable
private fun MonthScreen(dark: Boolean) = XrnTheme(dark = dark) {
    val ym = YearMonth.of(2026, 11)
    val grid = MonthLayout.weeks(ym, DayOfWeek.MONDAY)
    Column(Modifier.fillMaxSize()) {
        Bar("November")
        MonthPage(ym, DayOfWeek.MONDAY, between(grid.first().first(), grid.last().last()), look(), {}, {})
    }
}

@Preview(name = "Month", widthDp = 400, heightDp = 860)
@Composable
fun MonthPreview() = MonthScreen(false)

@Preview(name = "Month, dark", widthDp = 400, heightDp = 860)
@Composable
fun MonthDarkPreview() = MonthScreen(true)

@Preview(name = "Day pop-up", widthDp = 400, heightDp = 860)
@Composable
fun DayPopupPreview() = XrnTheme(dark = false) {
    Box(Modifier.fillMaxSize()) {
        MonthScreen(false)
        val day = LocalDate.of(2026, 11, 24)
        DayPopup(day, com.xern.xrncal.domain.Agenda.sortDay(between(day, day), day), look(), {}, {}, {})
    }
}

@Composable
private fun ListScreen(dark: Boolean) = XrnTheme(dark = dark) {
    val ym = YearMonth.of(2026, 11)
    val grid = MonthLayout.weeks(ym, DayOfWeek.MONDAY, rows = null)
    Column(Modifier.fillMaxSize()) {
        Bar("November")
        ListPage(ym, DayOfWeek.MONDAY, between(grid.first().first(), grid.last().last()), between(TODAY, TODAY.plusMonths(2)), TODAY, TODAY.plusMonths(2), look(), {}, {})
    }
}

@Preview(name = "List", widthDp = 400, heightDp = 860)
@Composable
fun ListPreview() = ListScreen(false)

@Preview(name = "List, dark", widthDp = 400, heightDp = 860)
@Composable
fun ListDarkPreview() = ListScreen(true)

@Preview(name = "Week", widthDp = 400, heightDp = 860)
@Composable
fun WeekPreview() = XrnTheme(dark = false) {
    val week = (0L until 7L).map { LocalDate.of(2026, 11, 23).plusDays(it) }
    Column(Modifier.fillMaxSize()) {
        Bar("November")
        TimeGridPage(week, between(week.first(), week.last()), look(), NOW, {}, { _, _ -> }, androidx.compose.foundation.rememberScrollState(400 * 3))
    }
}

@Preview(name = "Day", widthDp = 400, heightDp = 860)
@Composable
fun DayPreview() = XrnTheme(dark = true) {
    Column(Modifier.fillMaxSize()) {
        Bar("November")
        TimeGridPage(listOf(TODAY), between(TODAY, TODAY), look(), NOW, {}, { _, _ -> }, androidx.compose.foundation.rememberScrollState(60 * 3 * 8))
    }
}

@Preview(name = "Detail", widthDp = 400, heightDp = 860)
@Composable
fun DetailPreview() = XrnTheme(dark = false) {
    val o = EVENTS.first { it.title == "Status briefing" && it.firstDay == LocalDate.of(2026, 11, 30) }.copy(notes = "Bring the made-up figures for the third quarter.")
    DetailScreen(o, DetailInfo(CALENDARS[0], ZONE, "FREQ=WEEKLY;BYDAY=MO", listOf(EventReminder(10), EventReminder(60)), null), look(), {}, {}, {}, {}, {})
}

@Preview(name = "Detail, conflict", widthDp = 400, heightDp = 860)
@Composable
fun ConflictPreview() = XrnTheme(dark = true) {
    val o = EVENTS.first { it.title == "Dentist" }
    DetailScreen(o, DetailInfo(CALENDARS[1], ZONE, null, listOf(EventReminder(30)), "conflict"), look(), {}, {}, {}, {}, {})
}

@Preview(name = "Editor", widthDp = 400, heightDp = 1200)
@Composable
fun EditorPreview() = XrnTheme(dark = false) {
    val state = EditorState(
        EventDraft("Phở with Tuấn", false, LocalDate.of(2026, 11, 27), LocalTime.of(18, 30), LocalDate.of(2026, 11, 27), LocalTime.of(20, 0), ZONE, "FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", listOf(EventReminder(30)), "Phở Made-up, District 3", ""),
        "family",
    )
    EditorScreen(state, false, CALENDARS.filter { !it.readOnly }, look(), Reminders.Defaults(10, null), {}, {})
}

@Preview(name = "Add", widthDp = 400, heightDp = 860)
@Composable
fun AddPreview() = XrnTheme(dark = true) {
    EditorScreen(EditorState(newDraft(TODAY, null, NOW, ZONE, 60), "home"), true, CALENDARS.filter { !it.readOnly }, look(), Reminders.Defaults(10, null), {}, {})
}

@Preview(name = "Drawer", widthDp = 400, heightDp = 860)
@Composable
fun DrawerPreview() = XrnTheme(dark = false) {
    DrawerContent(View.MONTH, CALENDARS, "caldav.example.com", false, {}, {}, {}, {}, { _, _ -> })
}

@Preview(name = "Search", widthDp = 400, heightDp = 860)
@Composable
fun SearchPreview() = XrnTheme(dark = false) {
    SearchScreen("pho", {}, EVENTS.filter { it.title.startsWith("Phở") }, look(), {}, {})
}

@Preview(name = "Settings", widthDp = 400, heightDp = 1300)
@Composable
fun SettingsPreview() = XrnTheme(dark = false) {
    SettingsScreen(
        Settings(weekStart = DayOfWeek.MONDAY), AccountView("https://caldav.example.com/", "alex", true, SyncStatus(lastSync = NOW.toEpochMilli()), 4),
        listOf(Problem("p1", "Dentist", "Home", "conflict")), CALENDARS.filter { HolidayCalendar.of(it.id) == null }, look(), false, null,
        {}, {}, { _, _, _ -> }, {}, {}, {}, {},
    )
}

@Suppress("unused")
private val keepInstant: Instant = NOW
