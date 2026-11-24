package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** `content://com.android.calendar/time/<millis>`: "open the calendar at this time", as clock widgets send it. */
object TimeLink {
    private val PATTERN = Regex("""^content://com\.android\.calendar/time/(-?\d{1,18})/?(?:[?#].*)?$""")

    /** The day the link names in [zone], or null for any other address. */
    fun day(uri: String, zone: ZoneId): LocalDate? {
        val ms = PATTERN.matchEntire(uri.trim())?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return runCatching { Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }.getOrNull()
    }
}
