package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The laptop app's natural-entry.test.ts, with the same titles and the same answers. */
class NaturalEntryTest {
    // Thursday 5 November 2026, weeks starting on Monday.
    private val today = LocalDate.of(2026, 11, 5)
    private val context = EntryContext(today, DayOfWeek.MONDAY)
    private fun parse(text: String) = NaturalEntry.parse(text, context)

    /** One title and what is expected of it; as vitest's toMatchObject, a field left null isn't compared. */
    private class Row(
        val text: String,
        val title: String,
        val date: String? = null,
        val start: String? = null,
        val end: String? = null,
        val durationMinutes: Int? = null,
        val allDay: Boolean? = null,
        val repeat: EntryRepeat? = null,
    )

    private fun row(
        text: String,
        title: String,
        date: String? = null,
        start: String? = null,
        end: String? = null,
        durationMinutes: Int? = null,
        allDay: Boolean? = null,
        repeat: EntryRepeat? = null,
    ) = Row(text, title, date, start, end, durationMinutes, allDay, repeat)

    /** Every row checked, and every mismatch reported at once. */
    private fun check(rows: List<Row>) {
        val wrong = mutableListOf<String>()
        for (r in rows) {
            val p = parse(r.text)
            if (p == null) {
                wrong += "${r.text}: nothing read"
                continue
            }
            val problems = listOfNotNull(
                "title ${p.title}".takeIf { p.title != r.title },
                "date ${p.date}".takeIf { r.date != null && p.date != LocalDate.parse(r.date) },
                "start ${p.start}".takeIf { r.start != null && p.start != LocalTime.parse(r.start) },
                "end ${p.end}".takeIf { r.end != null && p.end != LocalTime.parse(r.end) },
                "duration ${p.durationMinutes}".takeIf { r.durationMinutes != null && p.durationMinutes != r.durationMinutes },
                "allDay ${p.allDay}".takeIf { r.allDay != null && p.allDay != r.allDay },
                "repeat ${p.repeat}".takeIf { r.repeat != null && p.repeat != r.repeat },
            )
            if (problems.isNotEmpty()) wrong += "${r.text}: $problems"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    private fun leavesAlone(vararg texts: String) {
        val read = texts.filter { parse(it) != null }.map { "$it -> ${parse(it)}" }
        assertEquals(emptyList<String>(), read)
    }

    // ─── Vietnamese ──────────────────────────────────────────────────────────────

    @Test
    fun readsVietnamese() = check(
        listOf(
            row("Họp 3h chiều thứ 6", "Họp", date = "2026-11-06", start = "15:00"),
            row("Họp 3pm thứ 6", "Họp", date = "2026-11-06", start = "15:00"),
            row("Hop 3h chieu thu 6", "Hop", date = "2026-11-06", start = "15:00"),
            row("Họp t6 lúc 9h", "Họp", date = "2026-11-06", start = "09:00"),
            row("Họp mai 9h", "Họp", date = "2026-11-06", start = "09:00"),
            row("Họp ngày mai lúc 8 giờ", "Họp", date = "2026-11-06", start = "08:00"),
            row("Họp ngày kia 10h", "Họp", date = "2026-11-07", start = "10:00"),
            row("tối nay xem phim 8h", "xem phim", date = "2026-11-05", start = "20:00"),
            row("Đi khám 8h sáng 20/11", "Đi khám", date = "2026-11-20", start = "08:00"),
            row("Nộp hồ sơ ngày 3 tháng 12 năm 2026", "Nộp hồ sơ", date = "2026-12-03"),
            row("Họp lúc 3 giờ rưỡi chiều", "Họp", start = "15:30"),
            row("Họp 3h", "Họp", start = "15:00"),
            row("Họp 05:30", "Họp", start = "05:30"),
            row("Họp 14h-15h30", "Họp", start = "14:00", end = "15:30"),
            row("Họp từ 9h đến 11h", "Họp", start = "09:00", end = "11:00"),
            row("Tiệc 22h-1h", "Tiệc", start = "22:00", end = "01:00"),
            row("Họp trong 2 tiếng lúc 9h", "Họp", start = "09:00", durationMinutes = 120),
            row("Nghỉ cả ngày thứ 6", "Nghỉ", date = "2026-11-06", allDay = true),
            row("Ăn tối 7h", "Ăn tối", start = "19:00"),
            row("Ăn trưa mai", "Ăn trưa", date = "2026-11-06"),
            row("Ăn sáng 7h", "Ăn sáng", start = "07:00"),
            row("Họp 8h tối nay", "Họp", date = "2026-11-05", start = "20:00"),
            row("Họp chiều mai 3h", "Họp", date = "2026-11-06", start = "15:00"),
            row("Ăn tối nay lúc 7h", "Ăn tối", date = "2026-11-05", start = "19:00"),
            row("Họp sáng thứ 2 lúc 9h", "Họp", date = "2026-11-09", start = "09:00"),
        ),
    )

    @Test
    fun readsThursdayAsTodayAndThursdayNextWeekAsNextWeek() {
        assertEquals(LocalDate.parse("2026-11-05"), parse("Họp thứ 5")?.date)
        assertEquals(LocalDate.parse("2026-11-12"), parse("Họp thứ 5 tuần sau")?.date)
        assertEquals(LocalDate.parse("2026-11-09"), parse("Họp thứ hai tuần tới")?.date)
        assertEquals(LocalDate.parse("2026-11-08"), parse("Đi chơi chủ nhật")?.date)
    }

    @Test
    fun putsADateThatHasPassedThisYearInTheNext() = check(listOf(row("Sinh nhật Mai 12/8", "Sinh nhật Mai", date = "2027-08-12")))

    @Test
    fun repeats() = check(
        listOf(
            row("Chạy bộ 6h sáng hàng ngày", "Chạy bộ", start = "06:00", repeat = EntryRepeat.Daily),
            row("Họp nhóm thứ 2 hàng tuần 9h30", "Họp nhóm", date = "2026-11-09", start = "09:30", repeat = EntryRepeat.Weekly(DayOfWeek.MONDAY)),
            row("Học tiếng Anh mỗi thứ 4 19h", "Học tiếng Anh", date = "2026-11-11", start = "19:00", repeat = EntryRepeat.Weekly(DayOfWeek.WEDNESDAY)),
            row("Đóng tiền nhà hàng tháng", "Đóng tiền nhà", repeat = EntryRepeat.Monthly),
        ),
    )

    /** The next date of a lunar day and month, from today on, as the laptop's test finds it. */
    private fun nextLunar(day: Int, month: Int, leap: Boolean = false): LocalDate {
        val spec = LunarSpec(day, month, leap)
        for (y in listOf(2026, 2027)) {
            val date = Lunar.resolve(spec, y)
            if (date != null && !date.isBefore(today)) return date
        }
        throw AssertionError("unplaceable")
    }

    @Test
    fun readsLunarDates() {
        val rows = listOf(
            Triple("Giỗ ông 12/8 âm lịch", "Giỗ ông", 12 to 8),
            Triple("Giỗ ông ngày 12 tháng 8 âm lịch", "Giỗ ông", 12 to 8),
            Triple("Giỗ bà 3/3 (âl)", "Giỗ bà", 3 to 3),
            Triple("Gio ong 12/8 am lich", "Gio ong", 12 to 8),
            Triple("Đi chùa rằm tháng 7", "Đi chùa", 15 to 7),
            Triple("Đi chùa mùng 1 Tết", "Đi chùa", 1 to 1),
            Triple("Giỗ bà 3 tháng chạp", "Giỗ bà", 3 to 12),
            Triple("Giỗ cụ mùng 10 tháng giêng", "Giỗ cụ", 10 to 1),
        )
        for ((text, title, dm) in rows) {
            val (day, month) = dm
            val p = parse(text)
            assertNotNull(text, p)
            val expected = ParsedEntry(
                title = title,
                date = nextLunar(day, month),
                allDay = true,
                repeat = EntryRepeat.LunarYearly(LunarSpec(day, month, false)),
                matched = p!!.matched,
            )
            assertEquals(text, expected, p)
        }
    }

    @Test
    fun keepsALeapMonth() {
        assertEquals(EntryRepeat.LunarYearly(LunarSpec(5, 4, true)), parse("Giỗ 5/4 nhuận âm lịch")?.repeat)
    }

    @Test
    fun leavesVietnameseThatIsNotADateAlone() = leavesAlone(
        "Phòng 3",
        "Phòng 301",
        "Họp 2 người",
        "Sinh nhật Mai",
        "Mua 3G",
        "Họp nay",
        "Dự án B3",
        // Nothing would be left of the title.
        "3h chiều mai",
        "Mùng 1 Tết",
    )

    // ─── English ─────────────────────────────────────────────────────────────────

    @Test
    fun readsEnglish() = check(
        listOf(
            row("Lunch with Sam tomorrow at 12:30", "Lunch with Sam", date = "2026-11-06", start = "12:30"),
            row("Dentist friday 10am", "Dentist", date = "2026-11-06", start = "10:00"),
            row("Dentist next friday 10am", "Dentist", date = "2026-11-13", start = "10:00"),
            row("Dentist on friday next week at 10am", "Dentist", date = "2026-11-13", start = "10:00"),
            row("Review Dec 3 2pm", "Review", date = "2026-12-03", start = "14:00"),
            row("Trip the 3rd of March", "Trip", date = "2027-03-03"),
            row("Trip on December 24th, 2026", "Trip", date = "2026-12-24"),
            row("Call in 3 days", "Call", date = "2026-11-08"),
            row("Offsite all day monday", "Offsite", date = "2026-11-09", allDay = true),
            row("Meeting 9-10:30am", "Meeting", start = "09:00", end = "10:30"),
            row("Meeting 3-5pm", "Meeting", start = "15:00", end = "17:00"),
            row("Meeting 11-1pm", "Meeting", start = "11:00", end = "13:00"),
            row("Meeting for 30 minutes at 4pm", "Meeting", start = "16:00", durationMinutes = 30),
            row("Dinner at 7", "Dinner", start = "19:00"),
            row("Team lunch at noon", "Team lunch", start = "12:00"),
            row("Movie tonight at 8", "Movie", date = "2026-11-05", start = "20:00"),
            row("Standup every monday 9:15", "Standup", date = "2026-11-09", start = "09:15", repeat = EntryRepeat.Weekly(DayOfWeek.MONDAY)),
            row("Water the plants weekly", "Water the plants", repeat = EntryRepeat.Weekly()),
        ),
    )

    @Test
    fun leavesEnglishThatIsNotADateAlone() = leavesAlone("I may be late", "Ship v2.0", "Room 12", "Sunset walk")

    @Test
    fun listsWhatItRecognisedAsTyped() {
        assertEquals(listOf("3h chiều", "thứ 6"), parse("Họp 3h chiều thứ 6")?.matched)
    }

    // ─── A title that starts with an emoji ───────────────────────────────────────
    // The editor's emoji button puts an emoji in front of the title. It is part of the title: what
    // the title says about when is read past it, and it stays.

    @Test
    fun readsPastAnEmojiLeadingTheTitle() = check(
        listOf(
            row("🍴 Lunch tomorrow at 12:30", "🍴 Lunch", date = "2026-11-06", start = "12:30"),
            row("🍴Lunch tomorrow at 12:30", "🍴Lunch", date = "2026-11-06", start = "12:30"),
            row("☕ Cà phê 3h chiều thứ 6", "☕ Cà phê", date = "2026-11-06", start = "15:00"),
            row("🎂 Sinh nhật Mai 12/8", "🎂 Sinh nhật Mai", date = "2027-08-12"),
            row(
                "🧑\u200D💻 Standup every monday 9:15",
                "🧑\u200D💻 Standup",
                date = "2026-11-09",
                start = "09:15",
                repeat = EntryRepeat.Weekly(DayOfWeek.MONDAY),
            ),
            row("❤\uFE0F Dinner at 7", "❤\uFE0F Dinner", start = "19:00"),
            row("🏃 Chạy bộ 6h sáng hàng ngày", "🏃 Chạy bộ", start = "06:00", repeat = EntryRepeat.Daily),
            row("🇻🇳 Trip on December 24th, 2026", "🇻🇳 Trip", date = "2026-12-24"),
            // A keycap is an emoji, not an hour or a day.
            row("1\uFE0F\u20E3 Review Dec 3 2pm", "1\uFE0F\u20E3 Review", date = "2026-12-03", start = "14:00"),
        ),
    )

    @Test
    fun readsTheSameDateAndTimeAsTheTitleWithoutTheEmoji() {
        for (text in listOf("Lunch with Sam tomorrow at 12:30", "Họp 3h chiều thứ 6", "Giỗ ông 12/8 âm lịch", "Meeting 3-5pm")) {
            val plain = parse(text)!!
            assertEquals(text, plain.copy(title = "🍴 ${plain.title}"), parse("🍴 $text"))
        }
    }

    @Test
    fun leavesATitleWithOnlyAnEmojiInFrontAlone() =
        leavesAlone("🍴 Lunch", "3\uFE0F\u20E3 Phòng 3", "🎂 Sinh nhật Mai", "☀\uFE0F Sunset walk")

    // ─── applyEntry ──────────────────────────────────────────────────────────────

    private val slot = EntrySlot(
        allDay = false,
        startDate = LocalDate.parse("2026-11-04"),
        startTime = LocalTime.parse("10:00"),
        endDate = LocalDate.parse("2026-11-04"),
        endTime = LocalTime.parse("11:30"),
    )

    private fun entry(
        date: String? = null,
        start: String? = null,
        end: String? = null,
        durationMinutes: Int? = null,
        allDay: Boolean = false,
        repeat: EntryRepeat? = null,
    ) = ParsedEntry(
        title = "x",
        date = date?.let(LocalDate::parse),
        start = start?.let(LocalTime::parse),
        end = end?.let(LocalTime::parse),
        durationMinutes = durationMinutes,
        allDay = allDay,
        repeat = repeat,
    )

    private fun slotOf(allDay: Boolean, startDate: String, startTime: String, endDate: String, endTime: String) =
        EntrySlot(allDay, LocalDate.parse(startDate), LocalTime.parse(startTime), LocalDate.parse(endDate), LocalTime.parse(endTime))

    @Test
    fun aTimeAloneKeepsTheDayAndTheLength() {
        assertEquals(slotOf(false, "2026-11-04", "15:00", "2026-11-04", "16:30"), NaturalEntry.applyEntry(slot, entry(start = "15:00")))
    }

    @Test
    fun aDayAloneKeepsTheHours() {
        val day = LocalDate.parse("2026-11-06")
        assertEquals(slot.copy(startDate = day, endDate = day), NaturalEntry.applyEntry(slot, entry(date = "2026-11-06")))
    }

    @Test
    fun anEndBeforeTheStartIsTheNextDay() {
        val out = NaturalEntry.applyEntry(slot, entry(start = "22:00", end = "01:00"))
        assertEquals(LocalDate.parse("2026-11-04"), out.startDate)
        assertEquals(LocalDate.parse("2026-11-05"), out.endDate)
        assertEquals(LocalTime.parse("01:00"), out.endTime)
    }

    @Test
    fun aTimeOnAnAllDaySlotMakesItAnHourLong() {
        val allDay = slot.copy(allDay = true, endDate = LocalDate.parse("2026-11-05"))
        assertEquals(slotOf(false, "2026-11-04", "09:00", "2026-11-04", "10:00"), NaturalEntry.applyEntry(allDay, entry(start = "09:00")))
    }

    @Test
    fun aTimeOnAnAllDaySlotMakesItAsLongAsNewEventsAreSetToBe() {
        val allDay = slot.copy(allDay = true, endDate = LocalDate.parse("2026-11-05"))
        val half = NaturalEntry.applyEntry(allDay, entry(start = "09:00"), 30)
        assertEquals(false, half.allDay)
        assertEquals(LocalTime.parse("09:00"), half.startTime)
        assertEquals(LocalTime.parse("09:30"), half.endTime)
        assertEquals(slotOf(false, "2026-11-04", "23:00", "2026-11-05", "01:00"), NaturalEntry.applyEntry(allDay, entry(start = "23:00"), 120))
        // A length or an end that was typed still wins, and a timed slot keeps its own.
        assertEquals(LocalTime.parse("09:45"), NaturalEntry.applyEntry(allDay, entry(start = "09:00", durationMinutes = 45), 30).endTime)
        assertEquals(LocalTime.parse("11:00"), NaturalEntry.applyEntry(allDay, entry(start = "09:00", end = "11:00"), 30).endTime)
        val timed = NaturalEntry.applyEntry(slot, entry(start = "15:00"), 30)
        assertEquals(LocalTime.parse("15:00"), timed.startTime)
        assertEquals(LocalTime.parse("16:30"), timed.endTime)
    }

    @Test
    fun aLengthAloneKeepsTheStart() {
        val out = NaturalEntry.applyEntry(slot, entry(durationMinutes = 45))
        assertEquals(LocalTime.parse("10:00"), out.startTime)
        assertEquals(LocalTime.parse("10:45"), out.endTime)
    }

    @Test
    fun aLunarDateIsOneAllDayDate() {
        val day = LocalDate.parse("2027-09-12")
        val lunar = entry(date = "2027-09-12", allDay = true, repeat = EntryRepeat.LunarYearly(LunarSpec(12, 8, false)))
        assertEquals(slot.copy(allDay = true, startDate = day, endDate = day), NaturalEntry.applyEntry(slot, lunar))
    }

    @Test
    fun allDayKeepsAMultiDaySpan() {
        val span = slot.copy(allDay = true, endDate = LocalDate.parse("2026-11-06"))
        val out = NaturalEntry.applyEntry(span, entry(date = "2026-11-10", allDay = true))
        assertEquals(LocalDate.parse("2026-11-10"), out.startDate)
        assertEquals(LocalDate.parse("2026-11-12"), out.endDate)
    }

    // ─── The phone's own: the draft and the repeat ───────────────────────────────

    @Test
    fun fillsADraftAndLeavesItsTitleAlone() {
        val draft = EventDraft(
            title = "Họp 3h chiều thứ 6",
            allDay = false,
            startDate = slot.startDate,
            startTime = slot.startTime,
            endDate = slot.endDate,
            endTime = slot.endTime,
            zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
        )
        val out = NaturalEntry.applyEntry(draft, parse(draft.title)!!)
        assertEquals(draft.copy(startDate = LocalDate.parse("2026-11-06"), startTime = LocalTime.parse("15:00"), endDate = LocalDate.parse("2026-11-06"), endTime = LocalTime.parse("16:30")), out)
    }

    @Test
    fun givesTheEditorsRepeat() {
        val friday = LocalDate.parse("2026-11-06")
        assertEquals(Repeat(Frequency.DAILY), EntryRepeat.Daily.toRepeat(friday))
        assertEquals(Repeat(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY)), EntryRepeat.Weekly(DayOfWeek.MONDAY).toRepeat(friday))
        // "Hàng tuần" alone: on the day it starts.
        assertEquals(Repeat(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.FRIDAY)), EntryRepeat.Weekly().toRepeat(friday))
        assertEquals(Repeat(Frequency.MONTHLY), EntryRepeat.Monthly.toRepeat(friday))
        assertEquals(Repeat(Frequency.YEARLY), EntryRepeat.Yearly.toRepeat(friday))
        assertNull(EntryRepeat.LunarYearly(LunarSpec(12, 8)).toRepeat(friday))
    }
}
