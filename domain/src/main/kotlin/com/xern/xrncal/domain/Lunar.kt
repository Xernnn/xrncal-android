package com.xern.xrncal.domain

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The Vietnamese lunar calendar (âm lịch), by Hồ Ngọc Đức's astronomical method at UTC+7: the
 * laptop app's lunar-vietnam.ts, line for line, so both show the same date on every day.
 */
data class LunarDate(val day: Int, val month: Int, val year: Int, val leap: Boolean, val jd: Int, val canChiYear: String)

/** A lunar anniversary: the same lunar day and month every year (a giỗ). */
data class LunarSpec(val day: Int, val month: Int, val leap: Boolean = false)

object Lunar {
    private val CAN = listOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
    private val CHI = listOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")

    private fun f(x: Double) = floor(x)

    fun jdFromDate(dd: Int, mm: Int, yy: Int): Int {
        val a = f((14 - mm) / 12.0)
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        var jd = dd + f((153 * m + 2) / 5.0) + 365 * y + f(y / 4.0) - f(y / 100.0) + f(y / 400.0) - 32045
        if (jd < 2299161) jd = dd + f((153 * m + 2) / 5.0) + 365 * y + f(y / 4.0) - 32083
        return jd.toInt()
    }

    fun jdToDate(jd: Int): LocalDate {
        val a: Double = if (jd > 2299160) {
            val alpha = f((jd - 1867216.25) / 36524.25)
            jd + 1 + alpha - f(alpha / 4)
        } else {
            jd.toDouble()
        }
        val b = a + 1524
        val c = f((b - 122.1) / 365.25)
        val d = f(365.25 * c)
        val e = f((b - d) / 30.6001)
        val day = (b - d - f(30.6001 * e)).toInt()
        val m = (if (e < 14) e - 1 else e - 13).toInt()
        var year = (if (m > 2) c - 4716 else c - 4715).toInt()
        if (year <= 0) year--
        return LocalDate.of(year, m, day)
    }

    private fun newMoonDay(k: Int, timeZone: Double = 7.0): Int {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        val jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3 +
            0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val ff = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        val c1 = (0.1734 - 0.000393 * t) * sin(m * dr) +
            0.0021 * sin(2 * dr * m) -
            0.4068 * sin(mpr * dr) +
            0.0161 * sin(2 * dr * mpr) -
            0.0004 * sin(3 * dr * mpr) +
            0.0104 * sin(2 * dr * ff) -
            0.0051 * sin((m + mpr) * dr) -
            0.0074 * sin((m - mpr) * dr) +
            0.0004 * sin((2 * ff + m) * dr) -
            0.0004 * sin((2 * ff - m) * dr) -
            0.0006 * sin((2 * ff + mpr) * dr) +
            0.001 * sin((2 * ff - mpr) * dr) +
            0.0005 * sin((2 * mpr + m) * dr)
        val deltat = if (t < -4) 0.00117 + 0.00165 * t + 0.0005 * t2 else 0.00011 + 0.0002 * t + 0.00018 * t2 - 0.000015 * t3
        val jdNew = jd1 + c1 - deltat
        return f(jdNew + 0.5 + timeZone / 24).toInt()
    }

    private fun sunLongitude(jdn: Int, timeZone: Double = 7.0): Int {
        val t = (jdn - 2451545.0 + 0.5 - timeZone / 24) / 36525
        val t2 = t * t
        val dr = PI / 180
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val m = 357.5291 + 35999.0503 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val c = (1.9146 - 0.004817 * t - 0.000014 * t2) * sin(dr * m) +
            (0.019993 - 0.000101 * t) * sin(dr * 2 * m) +
            0.00029 * sin(dr * 3 * m)
        var theta = (l0 + c) % 360
        if (theta < 0) theta += 360
        return f(theta / 30).toInt()
    }

    private fun lunarMonth11(yy: Int, timeZone: Double = 7.0): Int {
        val off = jdFromDate(31, 12, yy) - 2415021
        val k = f(off / 29.530588853).toInt()
        var nm = newMoonDay(k, timeZone)
        if (sunLongitude(nm, timeZone) >= 9) nm = newMoonDay(k - 1, timeZone)
        return nm
    }

    private fun leapMonthOffset(a11: Int, timeZone: Double = 7.0): Int {
        val k = f((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var last: Int
        var i = 1
        var arc = sunLongitude(newMoonDay(k + i, timeZone), timeZone)
        do {
            last = arc
            i++
            arc = sunLongitude(newMoonDay(k + i, timeZone), timeZone)
        } while (arc != last && i < 14)
        return i - 1
    }

    fun ofSolar(date: LocalDate): LunarDate = solarToLunar(date.dayOfMonth, date.monthValue, date.year)

    fun solarToLunar(dd: Int, mm: Int, yy: Int, timeZone: Double = 7.0): LunarDate {
        val jd = jdFromDate(dd, mm, yy)
        val k = f((jd - 2415021.076998695) / 29.530588853).toInt()
        var dayNumber = newMoonDay(k + 1, timeZone)
        if (dayNumber > jd) dayNumber = newMoonDay(k, timeZone)
        var a11 = lunarMonth11(yy, timeZone)
        var b11 = a11
        var lunarYear: Int
        if (a11 >= dayNumber) {
            lunarYear = yy
            a11 = lunarMonth11(yy - 1, timeZone)
        } else {
            lunarYear = yy + 1
            b11 = lunarMonth11(yy + 1, timeZone)
        }
        val lunarDay = jd - dayNumber + 1
        val diff = f((dayNumber - a11) / 29.0).toInt()
        var lunarMonth = diff + 11
        var leap = false
        if (b11 - a11 > 365) {
            val leapOffset = leapMonthOffset(a11, timeZone)
            if (diff >= leapOffset) {
                lunarMonth = diff + 10
                if (diff == leapOffset) leap = true
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        if (lunarMonth >= 11 && diff < 4) lunarYear--
        val can = CAN[(lunarYear + 6) % 10]
        val chi = CHI[(lunarYear + 8) % 12]
        return LunarDate(lunarDay, lunarMonth, lunarYear, leap, jd, "$can $chi")
    }

    data class Label(val label: String, val isFirstDay: Boolean, val isFullMoon: Boolean, val isTet: Boolean)

    /** The small date a day cell carries: `1/8` on the first of a month, `15/8` at the full moon, else the day. */
    fun label(d: LunarDate): Label {
        val first = d.day == 1
        val full = d.day == 15
        val leap = if (d.leap) "*" else ""
        val text = when {
            first -> "1/${d.month}$leap"
            full -> "15/${d.month}$leap"
            else -> "${d.day}"
        }
        return Label(text, first, full, first && d.month == 1)
    }

    /** The long form for a day header and an event's detail: `6/7 Bính Ngọ`, `15/6* Ất Tỵ` for a leap month. */
    fun longLabel(d: LunarDate): String = "${d.day}/${d.month}${if (d.leap) "*" else ""} ${d.canChiYear}"

    /**
     * The solar date of a lunar one, found by walking the days from 1 January of [lunarYear]. The
     * walk covers 430 days: a lunar year can end as late as 20 February of the next solar year.
     * (The laptop app walks 400, which ends near 5 February, so its 23/12 and 30/12 of a late year,
     * such as Ông Táo and Giao Thừa before Tết 2026, are never found.)
     */
    fun lunarToSolar(lunarDay: Int, lunarMonth: Int, lunarYear: Int, lunarLeap: Boolean = false, timeZone: Double = 7.0): LocalDate? {
        val jdStart = jdFromDate(1, 1, lunarYear)
        for (i in 0 until 430) {
            val solar = jdToDate(jdStart + i)
            val lunar = solarToLunar(solar.dayOfMonth, solar.monthValue, solar.year, timeZone)
            if (lunar.day == lunarDay && lunar.month == lunarMonth && lunar.year == lunarYear && lunar.leap == lunarLeap) return solar
        }
        return null
    }

    private val occurrenceCache = HashMap<String, LocalDate?>()

    /**
     * A yearly lunar anniversary's Gregorian date in [gregorianYear], or null when it can't be
     * placed in that year. A leap-month one in a year without that leap month falls back to the
     * ordinary month; a day-30 one in a 29-day month to day 29.
     */
    @Synchronized
    fun resolve(spec: LunarSpec, gregorianYear: Int): LocalDate? {
        val key = "${spec.day}-${spec.month}-${if (spec.leap) 1 else 0}@$gregorianYear"
        if (occurrenceCache.containsKey(key)) return occurrenceCache[key]
        val candidates = mutableListOf(Triple(spec.day, spec.month, spec.leap))
        if (spec.leap) candidates += Triple(spec.day, spec.month, false)
        if (spec.day == 30) {
            candidates += Triple(29, spec.month, spec.leap)
            if (spec.leap) candidates += Triple(29, spec.month, false)
        }
        var result: LocalDate? = null
        outer@ for (lunarYear in listOf(gregorianYear, gregorianYear - 1)) {
            for ((d, m, leap) in candidates) {
                val solar = lunarToSolar(d, m, lunarYear, leap)
                if (solar != null && solar.year == gregorianYear) {
                    result = solar
                    break@outer
                }
            }
        }
        occurrenceCache[key] = result
        return result
    }

    /** The next [count] dates of an anniversary from [from] on, that day included. */
    fun next(spec: LunarSpec, from: LocalDate, count: Int): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var year = from.year
        while (out.size < count && year <= from.year + count + 2) {
            resolve(spec, year)?.takeIf { !it.isBefore(from) }?.let { out += it }
            year++
        }
        return out
    }
}
