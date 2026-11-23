package com.xern.xrncal.domain

import java.time.LocalDate

/**
 * The holiday calendars the laptop app offers (holiday-calendars.ts): Vietnam's public and lunar
 * holidays, and a few international days. Worked out on the phone for any year, never stored or
 * synced; each shows as a read-only calendar of its own that the drawer turns on and off.
 */
enum class HolidayCalendar(val id: String, val title: String, val color: Int) {
    VIETNAM("holidays:vietnam", "Ngày lễ Việt Nam", 0xFFEB5757.toInt()),
    INTERNATIONAL("holidays:international", "International Holidays", 0xFF529CCA.toInt()),
    ;

    companion object {
        fun of(id: String): HolidayCalendar? = entries.firstOrNull { it.id == id }
    }
}

data class Holiday(val title: String, val date: LocalDate, val notes: String, val color: Int)

object Holidays {
    private fun c(hex: Long) = hex.toInt()

    private class Solar(val day: Int, val month: Int, val title: String, val notes: String, val color: Int)
    private class LunarDay(val day: Int, val month: Int, val title: String, val notes: String, val color: Int)

    private val VIETNAM_SOLAR = listOf(
        Solar(1, 1, "Tết Dương Lịch (New Year)", "Nghỉ lễ toàn quốc", c(0xFFEB5757)),
        Solar(14, 2, "Lễ Tình nhân (Valentine)", "Ngày lễ Tình yêu", c(0xFFE06F9F)),
        Solar(8, 3, "Quốc tế Phụ nữ", "International Women's Day", c(0xFFE06F9F)),
        Solar(30, 4, "Ngày Giải phóng Miền Nam", "Reunification Day", c(0xFFEB5757)),
        Solar(1, 5, "Quốc tế Lao động", "International Workers' Day", c(0xFFEB5757)),
        Solar(1, 6, "Quốc tế Thiếu nhi", "International Children's Day", c(0xFF529CCA)),
        Solar(27, 7, "Ngày Thương binh Liệt sĩ", "Tưởng niệm anh hùng liệt sĩ", c(0xFFEA9A5F)),
        Solar(19, 8, "Ngày Cách mạng Tháng Tám", "August Revolution", c(0xFFEA9A5F)),
        Solar(2, 9, "Quốc khánh Việt Nam", "National Day of Vietnam", c(0xFFEB5757)),
        Solar(20, 10, "Ngày Phụ nữ Việt Nam", "Vietnamese Women's Day", c(0xFFE06F9F)),
        Solar(20, 11, "Ngày Nhà giáo Việt Nam", "Vietnamese Teachers' Day", c(0xFF52B788)),
        Solar(22, 12, "Ngày Thành lập Quân đội Nhân dân VN", "VPA Foundation Day", c(0xFFEA9A5F)),
        Solar(24, 12, "Đêm Lễ Giáng sinh (Christmas Eve)", "Giáng sinh", c(0xFF52B788)),
        Solar(25, 12, "Lễ Giáng sinh (Christmas Day)", "Giáng sinh", c(0xFF52B788)),
    )

    private val VIETNAM_LUNAR = listOf(
        LunarDay(23, 12, "Ông Táo về Trời (23 Tháng Chạp)", "Tiễn Táo Quân về trời", c(0xFFEA9A5F)),
        LunarDay(30, 12, "Đêm Giao Thừa / Tất Niên", "Giao thừa Tết Nguyên Đán", c(0xFFEB5757)),
        LunarDay(1, 1, "Mùng 1 Tết Nguyên Đán", "Tết Cổ Truyền - Đầu năm mới", c(0xFFEB5757)),
        LunarDay(2, 1, "Mùng 2 Tết Nguyên Đán", "Tết Cổ Truyền", c(0xFFEB5757)),
        LunarDay(3, 1, "Mùng 3 Tết Nguyên Đán", "Tết Cổ Truyền", c(0xFFEB5757)),
        LunarDay(15, 1, "Tết Nguyên Tiêu (Rằm Tháng Giêng)", "Rằm đầu năm", c(0xFFEA9A5F)),
        LunarDay(10, 3, "Giỗ Tổ Hùng Vương", "Quốc lễ Giỗ Tổ Hùng Vương (10/3 Âm)", c(0xFFEB5757)),
        LunarDay(15, 4, "Đại lễ Phật Đản (Vesak)", "Rằm tháng Tư", c(0xFFEA9A5F)),
        LunarDay(5, 5, "Tết Đoan Ngọ (Giết sâu bọ)", "Mùng 5 tháng 5 Âm", c(0xFF52B788)),
        LunarDay(15, 7, "Lễ Vu Lan Báo Hiếu (Rằm Tháng Bảy)", "Xá tội vong nhân", c(0xFFEA9A5F)),
        LunarDay(15, 8, "Tết Trung Thu (Rằm Tháng Tám)", "Tết Thiếu nhi / Trông trăng", c(0xFF529CCA)),
    )

    fun vietnam(year: Int): List<Holiday> {
        val list = mutableListOf<Holiday>()
        for (h in VIETNAM_SOLAR) list += Holiday(h.title, LocalDate.of(year, h.month, h.day), h.notes, h.color)
        for (h in VIETNAM_LUNAR) {
            // 23/12 and 30/12 fall in January or February, in the lunar year before.
            val lunarYear = if (h.month == 12) year - 1 else year
            val solar = Lunar.lunarToSolar(h.day, h.month, lunarYear) ?: if (h.day == 30) Lunar.lunarToSolar(29, h.month, lunarYear) else null
            if (solar != null) list += Holiday(h.title, solar, "${h.notes} (${h.day}/${h.month} Âm lịch)", h.color)
        }
        return list.sortedBy { it.date }
    }

    fun international(year: Int): List<Holiday> = listOf(
        Holiday("New Year's Day", LocalDate.of(year, 1, 1), "Global Holiday", c(0xFFEB5757)),
        Holiday("Valentine's Day", LocalDate.of(year, 2, 14), "Celebration of love", c(0xFFE06F9F)),
        Holiday("Earth Day", LocalDate.of(year, 4, 22), "Environmental awareness", c(0xFF52B788)),
        Holiday("International Workers' Day", LocalDate.of(year, 5, 1), "Labour Day", c(0xFFEB5757)),
        Holiday("Halloween", LocalDate.of(year, 10, 31), "Costume & treats", c(0xFFEA9A5F)),
        Holiday("Christmas Eve", LocalDate.of(year, 12, 24), "Christmas Eve", c(0xFF52B788)),
        Holiday("Christmas Day", LocalDate.of(year, 12, 25), "Christmas celebration", c(0xFFEB5757)),
        Holiday("New Year's Eve", LocalDate.of(year, 12, 31), "End of year celebration", c(0xFF9A6DD7)),
    ).sortedBy { it.date }

    fun of(calendar: HolidayCalendar, year: Int): List<Holiday> = when (calendar) {
        HolidayCalendar.VIETNAM -> vietnam(year)
        HolidayCalendar.INTERNATIONAL -> international(year)
    }

    /** Every holiday of [calendar] from [from] to [to], inclusive. */
    fun between(calendar: HolidayCalendar, from: LocalDate, to: LocalDate): List<Holiday> =
        (from.year..to.year).flatMap { of(calendar, it) }.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
}
