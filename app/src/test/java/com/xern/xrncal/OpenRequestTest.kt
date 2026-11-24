package com.xern.xrncal

import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.MemoryAccount
import com.xern.xrncal.data.MemoryCalendarStore
import com.xern.xrncal.data.MemoryRungLog
import com.xern.xrncal.data.MemorySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A reminder's notification or an agenda widget's event, tapped while the app is closed: the
 * activity asks for the event before the calendar's first frame, when nothing collects yet. The
 * request waits for the calendar and is handled once (0.3.1 dropped it, and the app opened on
 * the calendar without the event).
 */
class OpenRequestTest {
    private val graph = Graph(
        store = MemoryCalendarStore(),
        settings = MemorySettings(),
        account = MemoryAccount(),
        alarms = object : AlarmScheduler {
            override fun set(at: Long) = Unit
            override fun cancel() = Unit
        },
        rungLog = MemoryRungLog(),
    )

    @Test
    fun anOpenAskedBeforeTheCalendarListensIsDeliveredOnceAndOnlyOnce() = runBlocking {
        graph.open("made-up-resource", 1_796_369_400_000L)
        assertEquals("made-up-resource" to 1_796_369_400_000L, withTimeoutOrNull(2_000) { graph.open.first() })
        assertNull(withTimeoutOrNull(300) { graph.open.first() })
    }

    @Test
    fun eachOpenIsDeliveredInTurn() = runBlocking {
        graph.open("first", null)
        assertEquals("first" to null, withTimeoutOrNull(2_000) { graph.open.first() })
        graph.open("second", 5L)
        assertEquals("second" to 5L, withTimeoutOrNull(2_000) { graph.open.first() })
        assertNull(withTimeoutOrNull(300) { graph.open.first() })
    }
}
