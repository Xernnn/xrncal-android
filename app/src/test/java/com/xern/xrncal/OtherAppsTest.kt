package com.xern.xrncal

import android.content.Intent
import android.provider.CalendarContract
import com.xern.xrncal.domain.InsertRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What other apps hand the calendar, read under the names Android gives it (docs/spec.md, "Other apps"). */
class OtherAppsTest {
    /** The domain reads the insert intent's extras by name; these are Android's own. */
    @Test
    fun theInsertRequestReadsAndroidsExtraNames() {
        assertEquals(CalendarContract.Events.TITLE, InsertRequest.TITLE)
        assertEquals(CalendarContract.Events.DESCRIPTION, InsertRequest.DESCRIPTION)
        assertEquals(CalendarContract.Events.EVENT_LOCATION, InsertRequest.LOCATION)
        assertEquals(CalendarContract.Events.RRULE, InsertRequest.RRULE)
        assertEquals(CalendarContract.Events.EVENT_TIMEZONE, InsertRequest.TIME_ZONE)
        assertEquals(CalendarContract.EXTRA_EVENT_BEGIN_TIME, InsertRequest.BEGIN)
        assertEquals(CalendarContract.EXTRA_EVENT_END_TIME, InsertRequest.END)
        assertEquals(CalendarContract.EXTRA_EVENT_ALL_DAY, InsertRequest.ALL_DAY)
        assertEquals(Intent.EXTRA_EMAIL, InsertRequest.EMAIL)
    }
}
