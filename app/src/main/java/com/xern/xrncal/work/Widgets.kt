package com.xern.xrncal.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import com.xern.xrncal.MainActivity
import com.xern.xrncal.R
import com.xern.xrncal.XrnCalApp
import com.xern.xrncal.data.Settings
import com.xern.xrncal.domain.Lunar
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
 * The home-screen widgets (docs/spec.md, phase 2 item 12): One Calendar's Agenda, a Month and the
 * date icon. RemoteViews, as the other xrn apps' widgets: the agenda's list uses Android 12's
 * RemoteCollectionItems, so no service is needed, and the month is drawn as one picture of the
 * widget's size. Text uses the system's sans-serif, which is MiSans on the owner's phone (a
 * launcher ignores font resources in a widget).
 */
object Widgets {
    const val MIDNIGHT = "com.xern.xrncal.WIDGETS_MIDNIGHT"

    private val L = Locale.ENGLISH

    /** Every widget drawn again, off the main thread; and the redraw at the next midnight booked. */
    fun refresh(context: Context) {
        val app = context.applicationContext as? XrnCalApp ?: return
        val manager = AppWidgetManager.getInstance(context)
        val ids = listOf(AgendaWidget::class.java, MonthWidget::class.java, DateWidget::class.java).associateWith { manager.getAppWidgetIds(ComponentName(context, it)) }
        if (ids.values.all { it.isEmpty() }) return
        val graph = app.graph
        graph.scope.launch {
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
                month = graph.events.occurrences(grid.first().first(), grid.last().last()),
            )
            for (id in (ids[AgendaWidget::class.java] ?: IntArray(0))) manager.updateAppWidget(id, agenda(context, input))
            for (id in (ids[MonthWidget::class.java] ?: IntArray(0))) manager.updateAppWidget(id, month(context, input, sizeOf(manager, id, 376, 280)))
            for (id in (ids[DateWidget::class.java] ?: IntArray(0))) manager.updateAppWidget(id, date(context, input))
            bookMidnight(context, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }

    /** The widget's size in dp, as the launcher reports it; [w] by [h] when it reports none. */
    private fun sizeOf(manager: AppWidgetManager, id: Int, w: Int, h: Int): Pair<Int, Int> {
        val o = manager.getAppWidgetOptions(id)
        val width = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: w
        val height = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: h
        return width to height
    }

    private fun bookMidnight(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 7, Intent(context, AgendaWidget::class.java).setAction(MIDNIGHT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.set(AlarmManager.RTC, at + 1_000, pending)
    }

    private fun openApp(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun frame(views: RemoteViews, s: Settings) {
        views.setInt(R.id.widget_bg, "setColorFilter", s.widgetBackground or 0xFF000000.toInt())
        views.setInt(R.id.widget_bg, "setImageAlpha", (s.widgetOpacity.coerceIn(0f, 1f) * 255).toInt())
    }

    /** "Tuesday 24 November", an agenda day's header. */
    fun dayTitle(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, L)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, L)}"

    /** The agenda's rows as views: what the widget's list holds, and what the previews draw. */
    fun agendaRows(context: Context, input: WidgetInput): List<Pair<Long, RemoteViews>> {
        val s = input.settings
        val rows = WidgetData.agenda(input.agenda, input.today, input.now, s.widgetHidden)
        return rows.mapIndexed { i, row ->
            when (row) {
                is WidgetData.AgendaRow.Day -> i.toLong() to RemoteViews(context.packageName, R.layout.widget_agenda_day).apply {
                    setTextViewText(R.id.row_day, dayTitle(row.date) + if (s.lunar) "  ·  " + Format.lunar(row.date) else "")
                    setTextColor(R.id.row_day, if (row.date == input.today) s.signal else s.widgetText)
                    // A header opens the app.
                    setOnClickFillInIntent(R.id.row_day, Intent())
                }
                is WidgetData.AgendaRow.Event -> i.toLong() to RemoteViews(context.packageName, R.layout.widget_agenda_event).apply {
                    val o = row.occurrence
                    setInt(R.id.row_color, "setColorFilter", input.color(o))
                    val time = when {
                        o.allDay -> "All day"
                        o.firstDay != row.date -> "…"
                        else -> Format.time(o.start.atZone(input.zone).toLocalTime(), input.h24)
                    }
                    setTextViewText(R.id.row_time, time)
                    setTextColor(R.id.row_time, s.widgetText and 0x00FFFFFF or 0xB3000000.toInt())
                    setTextViewText(R.id.row_title, o.title.ifBlank { "(no title)" })
                    setTextColor(R.id.row_title, s.widgetText)
                    // Tapping an event opens it in the app.
                    setOnClickFillInIntent(R.id.row_event, Intent().putExtra(MainActivity.OPEN, o.resourceId).putExtra(MainActivity.START, o.start.toEpochMilli()))
                }
            }
        }
    }

    fun agenda(context: Context, input: WidgetInput): RemoteViews {
        val s = input.settings
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        frame(views, s)
        views.setTextViewText(R.id.widget_title, dayTitle(input.today))
        views.setTextColor(R.id.widget_title, s.widgetText)
        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, 1))
        val rows = agendaRows(context, input)
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2).apply { for ((id, v) in rows) addItem(id, v) }.build()
        views.setRemoteAdapter(R.id.widget_list, items)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setTextColor(R.id.widget_empty, s.widgetText)
        // The fill-in on each event names it; the template must be mutable to take it.
        val template = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setPendingIntentTemplate(R.id.widget_list, template)
        return views
    }

    fun date(context: Context, input: WidgetInput, clicks: Boolean = true): RemoteViews {
        val s = input.settings
        val views = RemoteViews(context.packageName, R.layout.widget_date)
        frame(views, s)
        views.setTextViewText(R.id.date_weekday, input.today.dayOfWeek.getDisplayName(TextStyle.SHORT, L))
        views.setTextColor(R.id.date_weekday, s.signal)
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
                text.color = if (today) s.signal else s.widgetText
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

/** A widget kind: draws itself through [Widgets.refresh], and takes the midnight redraw. */
abstract class XrnWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = Widgets.refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.MIDNIGHT) Widgets.refresh(context) else super.onReceive(context, intent)
    }
}

class AgendaWidget : XrnWidget()

class MonthWidget : XrnWidget()

class DateWidget : XrnWidget()
