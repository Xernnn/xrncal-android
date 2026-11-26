package com.xern.xrncal.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.xern.xrncal.MainActivity
import com.xern.xrncal.R
import com.xern.xrncal.XrnCalApp
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.Lunar
import com.xern.xrncal.domain.Moon
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.WeekStart
import com.xern.xrncal.domain.WidgetData
import com.xern.xrncal.ui.Format
import com.xern.xrncal.ui.firstDayOf
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a widget is drawn from: the settings, today, now, and the events of the days it shows. */
class WidgetInput(
    val settings: Settings,
    val today: LocalDate,
    val now: Instant,
    val h24: Boolean,
    val colors: Map<String, Int>,
    val agenda: List<Occurrence>,
    val month: List<Occurrence>,
    /** The zone the times are written in: the one the events were expanded in. */
    val zone: java.time.ZoneId = settings.zoneId(),
) {
    fun color(o: Occurrence): Int = o.color ?: colors[o.calendarId] ?: 0xFF529CCA.toInt()
}

/**
 * The home-screen widgets (docs/spec.md, phase 2 item 12): the Agenda (the days ahead as date
 * columns and event chips), a Month and the date icon. RemoteViews,
 * as the other xrn apps' widgets: the agenda's list uses Android 12's RemoteCollectionItems, so
 * no service is needed, and the month is drawn as one picture of the widget's size. Text uses
 * the system's sans-serif, which is MiSans on the owner's phone (a launcher ignores font
 * resources in a widget).
 */
object Widgets {
    const val MIDNIGHT = "com.xern.xrncal.WIDGETS_MIDNIGHT"

    /** An event on the agenda's list ends: the agenda drawn again. */
    const val TICK = "com.xern.xrncal.WIDGETS_TICK"

    /** When an alarm is for, in epoch milliseconds: it may be set to go off before it. */
    const val DUE = "due"

    private val L = Locale.ENGLISH

    /**
     * Every widget drawn again, off the main thread, and the redraw at the next midnight booked;
     * with [agendaOnly], the agenda alone (its list's redraws). The agenda books its own next
     * redraw, when an event on its list next ends ([WidgetData.nextRedraw]), or cancels it when
     * nothing will. Null when there is nothing to draw.
     */
    fun refresh(context: Context, agendaOnly: Boolean = false): Job? {
        val app = context.applicationContext as? XrnCalApp ?: return null
        val manager = AppWidgetManager.getInstance(context)
        val ids = listOf(AgendaWidget::class.java, MonthWidget::class.java, DateWidget::class.java).associateWith { manager.getAppWidgetIds(ComponentName(context, it)) }
        val agendaIds = ids[AgendaWidget::class.java] ?: IntArray(0)
        if (agendaIds.isEmpty()) cancel(context, TICK)
        if (if (agendaOnly) agendaIds.isEmpty() else ids.values.all { it.isEmpty() }) return null
        val graph = app.graph
        return graph.scope.launch {
            val s = graph.settings.value
            val zone = s.zoneId()
            val now = Instant.now()
            val today = now.atZone(zone).toLocalDate()
            val firstDay = firstDayOf(s.weekStart)
            val grid = MonthLayout.weeks(YearMonth.from(today), firstDay, rows = null)
            val colors = graph.events.calendars().associate { it.id to it.color }
            val input = WidgetInput(
                s, today, now, Format.is24(context, s.clock), colors,
                agenda = graph.events.occurrences(today, today.plusDays(WidgetData.AGENDA_DAYS - 1)),
                month = if (agendaOnly) emptyList() else graph.events.occurrences(grid.first().first(), grid.last().last()),
            )
            if (agendaIds.isNotEmpty()) {
                val view = agendaView(input)
                for (id in agendaIds) manager.updateAppWidget(id, agenda(context, input, view, clicks = true))
                val next = WidgetData.nextRedraw(view, now)
                if (next != null) book(context, TICK, next.toEpochMilli()) else cancel(context, TICK)
            }
            if (agendaOnly) return@launch
            for (id in (ids[MonthWidget::class.java] ?: IntArray(0))) manager.updateAppWidget(id, month(context, input, portraitSize(manager.getAppWidgetOptions(id), 376, 280)))
            for (id in (ids[DateWidget::class.java] ?: IntArray(0))) manager.updateAppWidget(id, date(context, input))
            book(context, MIDNIGHT, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 1_000)
        }
    }

    /**
     * A widget alarm went off: drawn again if it is [DUE]; set again for the rest of the way if it
     * came early ([WidgetData.alarmFor]), nothing drawn. Null when nothing is being drawn.
     */
    fun alarm(context: Context, intent: Intent): Job? {
        val action = intent.action ?: return null
        val due = intent.getLongExtra(DUE, 0)
        if (System.currentTimeMillis() < due) {
            book(context, action, due)
            return null
        }
        return refresh(context, agendaOnly = action == TICK)
    }

    /**
     * The widget's size in dp on a portrait home screen, as the launcher reports it: its MIN_WIDTH
     * by its MAX_HEIGHT (MAX_WIDTH is the landscape width, which drew the month wider than its
     * cell and squashed it to fit); [w] by [h] when it reports none.
     */
    internal fun portraitSize(o: android.os.Bundle, w: Int, h: Int): Pair<Int, Int> {
        val width = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: w
        val height = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: h
        return width to height
    }

    /**
     * The widgets' alarms, [MIDNIGHT] and the agenda's [TICK]: non-wakeup and inexact, so they
     * never wake the phone (one asleep has its widgets redrawn as it wakes) and need no exact-alarm
     * permission. Set early enough that even their latest is on time ([WidgetData.alarmFor]).
     */
    private fun alarmIntent(context: Context, action: String, due: Long? = null, create: Boolean = true): PendingIntent? = PendingIntent.getBroadcast(
        context, if (action == TICK) 8 else 7,
        Intent(context, AgendaWidget::class.java).setAction(action).apply { if (due != null) putExtra(DUE, due) },
        PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE,
    )

    private fun book(context: Context, action: String, due: Long) {
        val at = WidgetData.alarmFor(Instant.ofEpochMilli(due), Instant.now()).toEpochMilli()
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, alarmIntent(context, action, due)!!)
    }

    private fun cancel(context: Context, action: String) {
        alarmIntent(context, action, create = false)?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    private fun openApp(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun frame(views: RemoteViews, s: Settings) {
        views.setInt(R.id.widget_bg, "setColorFilter", s.widgetBackground or 0xFF000000.toInt())
        views.setInt(R.id.widget_bg, "setImageAlpha", (s.widgetOpacity.coerceIn(0f, 1f) * 255).toInt())
    }

    /** "Tuesday 24 November", a day as the agenda reads it out. */
    fun dayTitle(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, L)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, L)}"

    /**
     * The agenda widget's colours. Its ground, text and today colour are Widget options'; the
     * rest follow the widget's theme, as its design has them: muted text, chips of the calendar's
     * colour (22 % in the dark, 14 % in the light), and the moons' teal and gold.
     */
    class AgendaInk(s: Settings) {
        val text = s.widgetText
        val muted = alpha(s.widgetText, 0xB3)
        val today = s.signalFor(s.widgetDark)
        val chipAlpha = if (s.widgetDark) 0x38 else 0x24
        val moonNew = if (s.widgetDark) 0xFF4FD1C1.toInt() else 0xFF0B7166.toInt()
        val moonFull = if (s.widgetDark) 0xFFF2C14E.toInt() else 0xFF8A5A00.toInt()
        val moonAlpha = if (s.widgetDark) 0x26 else 0x1F

        companion object {
            fun alpha(color: Int, a: Int): Int = color and 0x00FFFFFF or (a shl 24)
        }
    }

    /** The agenda as the widget shows it, its times written as the rest of the app writes them. */
    fun agendaView(input: WidgetInput): WidgetData.AgendaView =
        WidgetData.agenda(input.agenda, input.today, input.now, input.zone, input.settings.widgetHidden, { Format.time(it, input.h24) })

    /** What tapping an event opens: the event, in the app (MainActivity's [MainActivity.OPEN]). */
    fun eventIntent(o: Occurrence): Intent = Intent().putExtra(MainActivity.OPEN, o.resourceId).putExtra(MainActivity.START, o.start.toEpochMilli())

    /** What tapping a day's date opens: the app's Day view on it, through the time link other apps use (at noon, clear of a zone's midnight). */
    fun dayIntent(d: LocalDate, zone: java.time.ZoneId): Intent =
        Intent(Intent.ACTION_VIEW, "content://com.android.calendar/time/${d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()}".toUri())

    /** The agenda's days as views: what the widget's list holds, each day's events nested in it as chips. */
    fun agendaRows(context: Context, input: WidgetInput, view: WidgetData.AgendaView = agendaView(input)): List<Pair<Long, RemoteViews>> {
        val s = input.settings
        val ink = AgendaInk(s)
        return view.days.map { day ->
            day.date.toEpochDay() to RemoteViews(context.packageName, R.layout.widget_agenda_day).apply {
                val today = day.date == input.today
                setTextViewText(R.id.day_weekday, day.weekday)
                setTextColor(R.id.day_weekday, if (today || day.weekend) ink.today else ink.muted)
                setTextViewText(R.id.day_number, "${day.date.dayOfMonth}")
                setTextColor(R.id.day_number, if (today) Color.WHITE else ink.text)
                setColorStateList(R.id.day_number, "setBackgroundTintList", ColorStateList.valueOf(if (today) ink.today else Color.TRANSPARENT))
                val lunar = if (s.lunar) WidgetData.lunar(day.date) else null
                setViewVisibility(R.id.day_lunar, if (lunar != null && lunar.moon == null) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.day_moon, if (lunar?.moon != null) View.VISIBLE else View.GONE)
                when (lunar?.moon) {
                    null -> if (lunar != null) {
                        setTextViewText(R.id.day_lunar, lunar.text)
                        setTextColor(R.id.day_lunar, ink.muted)
                    }
                    else -> {
                        val color = if (lunar.moon == Moon.NEW) ink.moonNew else ink.moonFull
                        setImageViewResource(R.id.day_moon_glyph, if (lunar.moon == Moon.NEW) R.drawable.widget_ring else R.drawable.widget_dot)
                        setInt(R.id.day_moon_glyph, "setColorFilter", color)
                        setTextViewText(R.id.day_moon_text, lunar.text)
                        setTextColor(R.id.day_moon_text, color)
                        setColorStateList(R.id.day_moon, "setBackgroundTintList", ColorStateList.valueOf(AgendaInk.alpha(color, ink.moonAlpha)))
                        setContentDescription(R.id.day_moon, if (lunar.text == "Tết") "Tết" else (if (lunar.moon == Moon.NEW) "New moon " else "Full moon ") + lunar.text)
                    }
                }
                setContentDescription(R.id.day_date, dayTitle(day.date))
                setOnClickFillInIntent(R.id.day_date, dayIntent(day.date, input.zone))
                // A recycled row may still hold another day's chips.
                removeAllViews(R.id.day_events)
                for (chip in day.chips) addView(R.id.day_events, chipView(context, input, ink, chip))
            }
        }
    }

    private fun chipView(context: Context, input: WidgetInput, ink: AgendaInk, chip: WidgetData.Chip): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_agenda_chip).apply {
            val color = input.color(chip.occurrence) or 0xFF000000.toInt()
            setColorStateList(R.id.chip, "setBackgroundTintList", ColorStateList.valueOf(AgendaInk.alpha(color, ink.chipAlpha)))
            setInt(R.id.chip_color, "setColorFilter", color)
            setTextViewText(R.id.chip_title, chip.title)
            setTextColor(R.id.chip_title, ink.text)
            setTextViewText(R.id.chip_time, chip.time)
            setTextColor(R.id.chip_time, ink.muted)
            setOnClickFillInIntent(R.id.chip, eventIntent(chip.occurrence))
        }

    /** The agenda widget. Without [clicks] (the previews) nothing is tappable. */
    fun agenda(context: Context, input: WidgetInput, clicks: Boolean = true): RemoteViews = agenda(context, input, agendaView(input), clicks)

    private fun agenda(context: Context, input: WidgetInput, view: WidgetData.AgendaView, clicks: Boolean): RemoteViews {
        val s = input.settings
        val ink = AgendaInk(s)
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        frame(views, s)
        val rows = agendaRows(context, input, view)
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1).apply { for ((id, v) in rows) addItem(id, v) }.build()
        views.setRemoteAdapter(R.id.widget_list, items)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setTextViewText(R.id.widget_empty, "No events")
        views.setTextColor(R.id.widget_empty, ink.muted)
        if (clicks) {
            // The empty part opens the app.
            views.setOnClickPendingIntent(R.id.widget_root, openApp(context, 1))
            // The fill-in on each event names it, on each date its day; the template must be mutable to take them.
            val template = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setPendingIntentTemplate(R.id.widget_list, template)
        }
        return views
    }

    fun date(context: Context, input: WidgetInput, clicks: Boolean = true): RemoteViews {
        val s = input.settings
        val views = RemoteViews(context.packageName, R.layout.widget_date)
        frame(views, s)
        views.setTextViewText(R.id.date_weekday, input.today.dayOfWeek.getDisplayName(TextStyle.SHORT, L))
        views.setTextColor(R.id.date_weekday, s.signalFor(s.widgetDark))
        views.setTextViewText(R.id.date_day, "${input.today.dayOfMonth}")
        views.setTextColor(R.id.date_day, s.widgetText)
        views.setTextViewText(R.id.date_lunar, if (s.lunar) Format.lunar(input.today) else input.today.month.getDisplayName(TextStyle.SHORT, L))
        views.setTextColor(R.id.date_lunar, s.widgetText and 0x00FFFFFF or 0xB3000000.toInt())
        if (clicks) views.setOnClickPendingIntent(R.id.widget_root, openApp(context, 3))
        return views
    }

    fun month(context: Context, input: WidgetInput, size: Pair<Int, Int>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_month)
        frame(views, input.settings)
        views.setImageViewBitmap(R.id.widget_month, monthPicture(input, size.first, size.second, context.resources.displayMetrics.density))
        views.setContentDescription(R.id.widget_month, YearMonth.from(input.today).month.getDisplayName(TextStyle.FULL, L))
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, 4))
        return views
    }

    /**
     * This month drawn at [widthDp] by [heightDp]: its name, the weekdays, and each day's number
     * (today's in the signal colour) with its events as coloured bars, as many lines as fit.
     */
    fun monthPicture(input: WidgetInput, widthDp: Int, heightDp: Int, density: Float): Bitmap {
        val s = input.settings
        val scale = minOf(density, 2.2f)
        val w = (widthDp * scale).toInt().coerceAtLeast(1)
        val h = (heightDp * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        fun dp(v: Float) = v * scale
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.widgetText; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val pad = dp(10f)
        val month = YearMonth.from(input.today)
        text.textSize = dp(15f)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(month.month.getDisplayName(TextStyle.FULL, L), pad, pad + dp(14f), text)
        val firstDay = firstDayOf(s.weekStart)
        val top = pad + dp(22f)
        val col = (w - 2 * pad) / 7f
        text.textSize = dp(10f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        text.alpha = 170
        WeekStart.order(firstDay).forEachIndexed { i, d -> c.drawText(d.getDisplayName(TextStyle.SHORT, L).take(2), pad + col * i + dp(3f), top + dp(10f), text) }
        text.alpha = 255
        val gridTop = top + dp(16f)
        val laneH = dp(9f)
        val laneGap = dp(2f)
        val dayH = dp(14f)
        val weeksCount = MonthLayout.weeks(month, firstDay, rows = null).size
        val rowH = (h - gridTop - pad / 2) / weeksCount
        val lanes = MonthLayout.lanes(rowH, dayH, laneH, laneGap)
        val weeks = WidgetData.month(input.month, month, firstDay, s.widgetHidden, lanes)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { color = s.widgetText; alpha = 40; strokeWidth = dp(0.7f) }
        weeks.forEachIndexed { r, week ->
            val y = gridTop + rowH * r
            c.drawLine(pad, y, w - pad, y, line)
            week.days.forEachIndexed { i, d ->
                val today = d == input.today
                text.textSize = dp(11f)
                text.typeface = Typeface.create(if (today) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                text.color = if (today) s.signalFor(s.widgetDark) else s.widgetText
                text.alpha = if (d.month == month.month) 255 else 110
                c.drawText("${d.dayOfMonth}" + if (week.more[i] > 0) "+${week.more[i]}" else "", pad + col * i + dp(3f), y + dp(11f), text)
                if (s.lunar && d.month == month.month && col > dp(36f)) {
                    val l = Lunar.label(Lunar.ofSolar(d)).label
                    text.textSize = dp(8f)
                    text.alpha = 140
                    c.drawText(l, pad + col * (i + 1) - text.measureText(l) - dp(2f), y + dp(10f), text)
                }
            }
            text.alpha = 255
            for (b in week.bars) {
                bar.color = input.color(b.occurrence)
                val left = pad + col * b.from + dp(1f)
                val right = pad + col * (b.to + 1) - dp(1f)
                val by = y + dayH + (laneH + laneGap) * b.lane
                c.drawRoundRect(RectF(left, by, right, by + laneH), dp(2f), dp(2f), bar)
                val label = b.occurrence.title
                text.textSize = dp(7.5f)
                text.color = if (androidx.core.graphics.ColorUtils.calculateLuminance(bar.color) > 0.6) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt()
                c.save()
                c.clipRect(left, by, right - dp(1f), by + laneH)
                c.drawText(label, left + dp(2f), by + laneH - dp(2f), text)
                c.restore()
            }
        }
        return bitmap
    }
}

/** A widget kind: draws itself through [Widgets.refresh], and takes the midnight and list redraws. */
abstract class XrnWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        Widgets.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Widgets.MIDNIGHT && intent.action != Widgets.TICK) return super.onReceive(context, intent)
        // Kept alive until the widgets are drawn: an alarm may be all that started the app.
        val job = Widgets.alarm(context, intent) ?: return
        val done = goAsync()
        job.invokeOnCompletion { done.finish() }
    }
}

class AgendaWidget : XrnWidget()

class MonthWidget : XrnWidget()

class DateWidget : XrnWidget()
