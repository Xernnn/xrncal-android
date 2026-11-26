package com.xern.xrncal

import android.appwidget.AppWidgetHostView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.TimeLink
import com.xern.xrncal.work.WidgetInput
import com.xern.xrncal.work.Widgets
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agenda widget as a launcher draws it: its RemoteViews applied by the framework's own code,
 * at a home screen's four by four, dark and light, with made-up events. Each case is also saved
 * as a picture (-Pshots=<dir>, or build/shots), to look at without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class AgendaWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val utc = ZoneOffset.UTC
    private val thursday = LocalDate.parse("2026-11-26")

    private val dark = Settings(widgetDark = true, widgetText = 0xFFECEEF3.toInt(), widgetBackground = 0xFF16181D.toInt(), widgetOpacity = 0.94f)
    private val light = Settings(widgetDark = false, widgetText = 0xFF1D1F24.toInt(), widgetBackground = 0xFFFFFFFF.toInt(), widgetOpacity = 0.96f)

    private val colors = mapOf(
        "work" to 0xFF3F51B5.toInt(), "uni" to 0xFF8E24AA.toInt(), "gym" to 0xFF795548.toInt(),
        "bills" to 0xFFF09300.toInt(), "fun" to 0xFFF6BF26.toInt(), "due" to 0xFFD50000.toInt(),
    )

    private fun timed(title: String, cal: String, day: String, from: String, to: String, place: String = "") = Occurrence(
        title, cal, "u-$title", null, title, place, "", false,
        Instant.parse("${day}T$from:00Z"), Instant.parse("${day}T$to:00Z"), LocalDate.parse(day), LocalDate.parse(day), false, false, null,
    )

    private fun allDay(title: String, cal: String, first: String, last: String = first) = Occurrence(
        title, cal, "u-$title", null, title, "", "", true,
        LocalDate.parse(first).atStartOfDay(utc).toInstant(), LocalDate.parse(last).plusDays(1).atStartOfDay(utc).toInstant(), LocalDate.parse(first), LocalDate.parse(last), false, false, null,
    )

    /** The mock-ups' fortnight: a standup at 9, more today, rent, a movie, an assignment at the new moon. */
    private val fortnight = listOf(
        timed("Team standup", "work", "2026-11-26", "09:00", "09:30", place = "Room 4"),
        timed("Design review", "uni", "2026-11-26", "11:00", "13:00"),
        timed("Gym", "gym", "2026-11-26", "17:30", "18:30"),
        allDay("Pay rent", "bills", "2026-11-27"),
        timed("Movie night", "fun", "2026-11-28", "19:00", "22:00"),
        timed("Assignment due", "due", "2026-12-09", "23:59", "23:59"),
    )

    private fun input(s: Settings, events: List<Occurrence>, now: String = "2026-11-26T08:35:00Z", today: LocalDate = thursday) =
        WidgetInput(s, today, Instant.parse(now), true, colors, agenda = events, month = emptyList(), zone = utc)

    /** The widget applied, laid out at [w] by [h] dp over the mock-ups' backdrop, and saved as [name].png. */
    private fun draw(name: String, input: WidgetInput, w: Int = 376, h: Int = 390, clicks: Boolean = false): View {
        val density = context.resources.displayMetrics.density
        // The framework fills a collection only in a widget host, as a launcher's.
        val host = AppWidgetHostView(context)
        host.addView(Widgets.agenda(context, input, clicks).apply(context, host))
        val wPx = (w * density).toInt()
        val hPx = (h * density).toInt()
        repeat(2) {
            host.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, wPx, hPx)
        }
        val pad = (32 * density).toInt()
        val bitmap = Bitmap.createBitmap(wPx + 2 * pad, hPx + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF2B2F38.toInt())
        canvas.translate(pad.toFloat(), pad.toFloat())
        host.draw(canvas)
        val dir = File(System.getProperty("xrncal.shots") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return host
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    /** Each day on the list: its weekday and date, and its chips' titles and times. */
    private fun days(root: View): List<String> {
        val list = root.findViewById<ListView>(R.id.widget_list)
        return (0 until list.childCount).map { i ->
            val row = list.getChildAt(i)
            val chips = row.findViewById<android.view.ViewGroup>(R.id.day_events)
            val lunar = listOf(R.id.day_lunar, R.id.day_moon_text).map { row.findViewById<TextView>(it) }.firstOrNull { (it.parent as View).visibility == View.VISIBLE && it.visibility == View.VISIBLE }?.text
            "${row.text(R.id.day_weekday)} ${row.text(R.id.day_number)} ${lunar ?: "-"}: " +
                (0 until chips.childCount).joinToString { c -> chips.getChildAt(c).let { "${it.text(R.id.chip_title)} (${it.text(R.id.chip_time)})" } }
        }
    }

    @Test
    fun theDaysAheadAreChipsTheNextEventOnItsDay() {
        for ((name, s) in listOf("agenda-dark" to dark, "agenda-light" to light, "agenda-defaults" to Settings())) {
            val root = draw(name, input(s, fortnight))
            assertEquals(
                listOf(
                    "THU 26 18/10: Team standup (09:00 – 09:30 · Room 4), Design review (11:00 – 13:00), Gym (17:30 – 18:30)",
                    "FRI 27 19/10: Pay rent (All day)",
                    "SAT 28 20/10: Movie night (19:00 – 22:00)",
                    "WED 9 1/11: Assignment due (23:59)",
                ),
                days(root),
            )
        }
    }

    @Test
    fun anEmptyDayIsLeftOut() {
        // Sunday 22 November: a trip over two days (the second the full moon, 15/10), rent on Friday.
        val events = listOf(allDay("Trip", "fun", "2026-11-22", "2026-11-23"), allDay("Pay rent", "bills", "2026-11-27"))
        val root = draw("agenda-all-day", input(dark, events, now = "2026-11-22T08:00:00Z", today = LocalDate.parse("2026-11-22")))
        assertEquals(listOf("SUN 22 14/10: Trip (All day)", "MON 23 15: Trip (All day)", "FRI 27 19/10: Pay rent (All day)"), days(root))
    }

    @Test
    fun withNothingComingTheWidgetSaysSo() {
        val root = draw("agenda-empty", input(dark, emptyList()), h = 200)
        assertEquals("No events", root.text(R.id.widget_empty))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
    }

    /** What tapping [view] starts. */
    private fun tap(view: View): android.content.Intent {
        assertTrue(view.performClick())
        return Shadows.shadowOf(context).nextStartedActivity
    }

    @Test
    fun aChipOpensItsEventAndADateItsDay() {
        val root = draw("agenda-taps", input(dark, fortnight), clicks = true)
        val row = root.findViewById<ListView>(R.id.widget_list).getChildAt(0)
        val day = tap(row.findViewById(R.id.day_date))
        assertEquals(android.content.Intent.ACTION_VIEW, day.action)
        assertEquals(thursday, TimeLink.day(day.data.toString(), utc))
        val chip = tap(row.findViewById<android.view.ViewGroup>(R.id.day_events).getChildAt(2).findViewById(R.id.chip))
        assertEquals(MainActivity::class.java.name, chip.component?.className)
        assertEquals("Gym", chip.getStringExtra(MainActivity.OPEN))
        assertEquals(Instant.parse("2026-11-26T17:30:00Z").toEpochMilli(), chip.getLongExtra(MainActivity.START, -1))
    }

    @Test
    fun anEventOnNowStaysAndTheLunarDateCanBeOff() {
        val root = draw("agenda-now-4x2", input(dark.copy(lunar = false), fortnight, now = "2026-11-26T11:20:00Z"), h = 200)
        assertEquals("THU 26 -: Design review (11:00 – 13:00), Gym (17:30 – 18:30)", days(root).first())
    }

    @Test
    fun anAlarmComingEarlyIsSetAgainForTheRestOfTheWayWithNothingDrawn() {
        val alarms = Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val due = System.currentTimeMillis() + 60_000
        val early = android.content.Intent(context, com.xern.xrncal.work.AgendaWidget::class.java).setAction(Widgets.TICK).putExtra(Widgets.DUE, due)
        assertNull(Widgets.alarm(context, early))
        val next = alarms.scheduledAlarms.single()
        assertEquals(android.app.AlarmManager.RTC, next.type)
        assertTrue(next.triggerAtTime in System.currentTimeMillis() + 30_000..due - 20_000)
        val again = Shadows.shadowOf(next.operation).savedIntent
        assertEquals(Widgets.TICK, again.action)
        assertEquals(due, again.getLongExtra(Widgets.DUE, 0))
    }
}
