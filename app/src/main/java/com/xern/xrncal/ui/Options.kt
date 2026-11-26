package com.xern.xrncal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.Clock
import com.xern.xrncal.data.HourSize
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.ThemeMode
import com.xern.xrncal.data.View
import com.xern.xrncal.domain.EventLength
import com.xern.xrncal.domain.Reminders
import java.time.DayOfWeek
import kotlinx.coroutines.launch

// ---------------- shared rows ----------------

/** A labelled slider: the value at the right, as One Calendar's widget opacity. */
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, text: String, onChange: (Float) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
            Text(text, style = type(15, color = ink.muted))
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = ink.accent, activeTrackColor = ink.accent),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** A row with a colour square at the right that opens the palette. */
@Composable
fun ColorRow(label: String, color: Int, onPick: (Int) -> Unit) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 12.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(3.dp)).background(Color(color)).border(1.dp, ink.line, RoundedCornerShape(3.dp)))
    }
    if (open) {
        ColorPickerDialog(label, color, null, onPick = { c ->
            if (c != null) onPick(c)
            open = false
        }, onDismiss = { open = false })
    }
}

// ---------------- Theme ----------------

/**
 * Options, Theme (docs/spec.md, phase 2 item 1): the wallpaper (no image, the laptop's four
 * presets, or one imported from the gallery), how dark its overlay is and how blurred, the app's
 * light or dark, and the signal colour.
 */
@Composable
fun ThemeOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { Wallpapers.import(context, uri)?.let { w -> onChange { it.copy(wallpaper = w) } } }
    }
    Section("Wallpaper")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WallpaperTile("No image", null, s.wallpaper == Wallpapers.NONE) { onChange { it.copy(wallpaper = Wallpapers.NONE) } }
        for (p in Wallpapers.PRESETS) {
            WallpaperTile(p.name, rememberWallpaper(p.setting, 240), s.wallpaper == p.setting) { onChange { it.copy(wallpaper = p.setting) } }
        }
        val imported = s.wallpaper.startsWith("file:")
        WallpaperTile("Import", if (imported) rememberWallpaper(s.wallpaper, 240) else null, imported, icon = true) {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    if (s.wallpaper != Wallpapers.NONE) {
        SliderRow("Overlay darkness", s.overlay, 0f..0.8f, 0, "%.2f".format(s.overlay)) { v -> onChange { it.copy(overlay = (v * 100).toInt() / 100f) } }
        SliderRow("Blur", s.blur.toFloat(), 0f..20f, 0, "${s.blur}") { v -> onChange { it.copy(blur = Math.round(v)) } }
    }
    Section("Colours")
    val themes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
    Choose("Application theme", themes.first { it.first == s.theme }.second, themes.map { it.second }) { i -> onChange { it.copy(theme = themes[i].first) } }
    // The swatch shows the colour as this theme draws it: the default's light or dark shade.
    ColorRow("Signal colour", ink.today.toArgb()) { c -> onChange { it.copy(signal = c) } }
    Text(
        "Default",
        style = type(14, FontWeight.Medium, ink.accent),
        modifier = Modifier.padding(start = 20.dp).clickable { onChange { it.copy(signal = Settings.SIGNAL_RED) } }.padding(vertical = 6.dp),
    )
}

@Composable
private fun WallpaperTile(name: String, image: ImageBitmap?, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.width(76.dp).clickable(onClick = onClick).semantics { contentDescription = name }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 72.dp, height = 112.dp).clip(RoundedCornerShape(6.dp)).background(ink.field)
                .border(if (selected) 2.dp else 1.dp, if (selected) ink.accent else ink.line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 72.dp, height = 112.dp))
            else if (icon) Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = ink.muted)
        }
        Text(name, style = type(12, color = ink.text), textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 4.dp).height(18.dp))
    }
}

// ---------------- the Options list and its pages ----------------

/** One Calendar's Options, in its order (Security and Task options left out). */
enum class OptionPage(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    VIEWS("Views", Icons.Outlined.ViewWeek),
    APPOINTMENTS("Appointments", Icons.Outlined.Description),
    COLORS("Calendar colors", Icons.Outlined.Palette),
    THEME("Theme", Icons.Outlined.Image),
    REMINDERS("Reminders", Icons.Outlined.Alarm),
    SYNC("Synchronisation", Icons.Outlined.Sync),
    ZONE("Time zone", Icons.Outlined.Language),
    WIDGET("Widget options", Icons.Outlined.Widgets),
    EMOJI("Emoji options", Icons.Outlined.EmojiEmotions),
    ABOUT("About", Icons.Outlined.Info),
}

@Composable
fun OptionsList(onPage: (OptionPage) -> Unit, onBack: () -> Unit) = OptionsFrame("Options", onBack) {
    val ink = LocalInk.current
    for (p in OptionPage.entries) {
        Row(Modifier.fillMaxWidth().clickable { onPage(p) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, contentDescription = null, tint = ink.muted)
            androidx.compose.foundation.layout.Spacer(Modifier.width(28.dp))
            Text(p.title, style = type(16, color = ink.text))
        }
    }
}

/** What an Options page changes beyond the settings: calendar colours. */
@Composable
fun OptionPageScreen(
    page: OptionPage,
    s: Settings,
    calendars: List<CalendarInfo>,
    onChange: ((Settings) -> Settings) -> Unit,
    onColor: (String, Int) -> Unit,
    onBack: () -> Unit,
) = OptionsFrame(page.title, onBack) {
    when (page) {
        OptionPage.VIEWS -> ViewOptions(s, onChange)
        OptionPage.APPOINTMENTS -> AppointmentOptions(s, calendars, onChange)
        OptionPage.COLORS -> {
            Section("Calendars")
            CalendarColors(calendars, onColor)
        }
        OptionPage.THEME -> ThemeOptions(s, onChange)
        OptionPage.REMINDERS -> ReminderOptions(s, onChange)
        OptionPage.SYNC -> SyncOptions(s, onChange)
        OptionPage.ZONE -> ZoneOptions(s, onChange)
        OptionPage.WIDGET -> WidgetOptions(s, calendars, onChange)
        OptionPage.EMOJI -> EmojiOptions(s, onChange)
        OptionPage.ABOUT -> About()
    }
}

/** Options, About: the version as "0.3.1 (6)" (its name and code) and the app id. */
fun aboutLines(versionName: String, versionCode: Int, appId: String): List<Pair<String, String>> =
    listOf("Version" to "$versionName ($versionCode)", "App id" to appId)

@Composable
private fun About() {
    val ink = LocalInk.current
    for ((label, value) in aboutLines(com.xern.xrncal.BuildConfig.VERSION_NAME, com.xern.xrncal.BuildConfig.VERSION_CODE, com.xern.xrncal.BuildConfig.APPLICATION_ID)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, style = type(16, color = ink.text))
            Text(value, style = type(14, color = ink.muted))
        }
    }
}

@Composable
private fun ViewOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Section("General")
    val weekStarts = listOf(null to "Phone", DayOfWeek.MONDAY to "Monday", DayOfWeek.SUNDAY to "Sunday", DayOfWeek.SATURDAY to "Saturday")
    Choose("First day of week", weekStarts.first { it.first == s.weekStart }.second, weekStarts.map { it.second }) { i -> onChange { it.copy(weekStart = weekStarts[i].first) } }
    val clocks = listOf(Clock.SYSTEM to "Phone", Clock.H24 to "24 hours", Clock.H12 to "12 hours")
    Choose("Time format", clocks.first { it.first == s.clock }.second, clocks.map { it.second }) { i -> onChange { it.copy(clock = clocks[i].first) } }
    Toggle("Show week numbers", s.weekNumbers) { on -> onChange { it.copy(weekNumbers = on) } }
    val views = listOf(View.LIST to "List", View.MONTH to "Month", View.WEEK to "Week", View.DAY to "Day", View.YEAR to "Year")
    Choose("Startup view", views.first { it.first == s.defaultView }.second, views.map { it.second }) { i -> onChange { it.copy(defaultView = views[i].first) } }
    Toggle("Lunar dates", s.lunar) { on -> onChange { it.copy(lunar = on) } }
    Section("Month view")
    Toggle("Show month day popup", s.monthPopup) { on -> onChange { it.copy(monthPopup = on) } }
    Toggle("Start times in month", s.monthTimes) { on -> onChange { it.copy(monthTimes = on) } }
    Section("Day and week view")
    Choose("Start time of day", "%02d:00".format(s.dayStart), (0..23).map { "%02d:00".format(it) }) { i -> onChange { it.copy(dayStart = i) } }
    Choose("Hour size", s.hourSize.label, HourSize.entries.map { it.label }) { i -> onChange { it.copy(hourSize = HourSize.entries[i]) } }
    var zoning by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { zoning = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Second time zone", style = type(16, color = LocalInk.current.text))
            Text(s.secondZone?.replace('_', ' ') ?: "None", style = type(14, color = LocalInk.current.muted))
        }
    }
    if (s.secondZone != null) {
        Text("None", style = type(14, FontWeight.Medium, LocalInk.current.accent), modifier = Modifier.padding(start = 20.dp).clickable { onChange { it.copy(secondZone = null) } }.padding(vertical = 4.dp))
    }
    if (zoning) ZonePick(s.secondZone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault(), s.preferredZones, { zoning = false }) { z -> onChange { it.copy(secondZone = z.id) } }
    Section("Editor")
    Choose("Time steps", "${s.timeStep} min", Settings.TIME_STEPS.map { "$it min" }) { i -> onChange { it.copy(timeStep = Settings.TIME_STEPS[i]) } }
}

@Composable
private fun AppointmentOptions(s: Settings, calendars: List<CalendarInfo>, onChange: ((Settings) -> Settings) -> Unit) {
    Section("Default calendar")
    val writable = calendars.filter { !it.readOnly }
    Choose("Calendar", writable.firstOrNull { it.id == s.defaultCalendar }?.name ?: writable.firstOrNull()?.name ?: "None", writable.map { it.name }) { i -> onChange { it.copy(defaultCalendar = writable[i].id) } }
    Section("Defaults")
    Choose("Default appointment length", lengthLabel(s.eventMinutes), EventLength.OPTIONS.map(::lengthLabel)) { i -> onChange { it.copy(eventMinutes = EventLength.OPTIONS[i]) } }
    Toggle("Suggestions", s.suggestions) { on -> onChange { it.copy(suggestions = on) } }
}

private fun lengthLabel(m: Int) = when {
    m == 60 -> "1 hour"
    m % 60 == 0 -> "${m / 60} hours"
    m > 60 -> "${m / 60} h ${m % 60} min"
    else -> "$m minutes"
}

@Composable
private fun ReminderOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    Section("Defaults")
    val choices = listOf<Int?>(null) + Reminders.ONE_CALENDAR_CHOICES
    Choose("Appointments", Reminders.choiceLabel(s.timedReminder), choices.map(Reminders::choiceLabel)) { i -> onChange { it.copy(timedReminder = choices[i]) } }
    Choose("All day appointments", Reminders.choiceLabel(s.allDayReminder), choices.map(Reminders::choiceLabel)) { i -> onChange { it.copy(allDayReminder = choices[i]) } }
    Section("Notifications")
    Toggle("Notification data sensitive", s.privateNotifications) { on -> onChange { it.copy(privateNotifications = on) } }
    Choose("Snooze", Reminders.choiceLabel(s.snoozeMinutes), Settings.SNOOZES.map(Reminders::choiceLabel)) { i -> onChange { it.copy(snoozeMinutes = Settings.SNOOZES[i]) } }
    Row(
        Modifier.fillMaxWidth().clickable {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Settings, contentDescription = null, tint = LocalInk.current.muted)
        androidx.compose.foundation.layout.Spacer(Modifier.width(28.dp))
        Text("Notification settings", style = type(16, color = LocalInk.current.text))
    }
}

@Composable
private fun SyncOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val history = Settings.HISTORY
    fun years(n: Int) = when (n) {
        0 -> "All"
        1 -> "1 year"
        else -> "$n years"
    }
    Choose("History", years(s.historyYears), history.map(::years)) { i -> onChange { it.copy(historyYears = history[i]) } }
    Choose("Automatic synchronisation", Reminders.choiceLabel(s.syncMinutes), Settings.SYNC_INTERVALS.map(Reminders::choiceLabel)) { i -> onChange { it.copy(syncMinutes = Settings.SYNC_INTERVALS[i]) } }
    BackgroundRows()
}

/**
 * What lets the background sync and the reminders run with the app closed: HyperOS's Autostart
 * (where the phone says) and the battery exemption, each opening its switch. Read again whenever
 * the page comes back to the front.
 */
@Composable
private fun BackgroundRows() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var autostart by remember { mutableStateOf<Boolean?>(null) }
    var battery by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        autostart = com.xern.xrncal.work.OemAutostart.enabled(context)
        battery = com.xern.xrncal.work.BatteryExemption.exempt(context)
        onPauseOrDispose { }
    }
    autostart?.let { on -> StateRow("Autostart", if (on) "On" else "Off", on) { runCatching { com.xern.xrncal.work.OemAutostart.open(context) } } }
    StateRow("Battery", if (battery) "Unrestricted" else "Optimised", battery) { com.xern.xrncal.work.BatteryExemption.ask(context) }
}

/** A setting the phone holds: its state, in the warning colour while it is off; a tap opens its switch. */
@Composable
private fun StateRow(label: String, value: String, ok: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = type(16, color = ink.text))
            Text(value, style = type(14, color = if (ok) ink.muted else ink.warn))
        }
    }
}

@Composable
private fun ZoneOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val ink = LocalInk.current
    var picking by remember { mutableStateOf(false) }
    val shown = s.zoneId()
    Section("Default time zone")
    Row(Modifier.fillMaxWidth().clickable(enabled = !s.deviceZone) { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Column {
            Text(shown.id.replace('_', ' '), style = type(16, color = if (s.deviceZone) ink.muted else ink.text))
            Text("(" + offsetLabel(shown.id) + ")", style = type(14, color = ink.muted))
        }
    }
    Section("Time zone options")
    Toggle("Use device time zone", s.deviceZone) { on -> onChange { it.copy(deviceZone = on, zone = it.zone ?: java.time.ZoneId.systemDefault().id) } }
    if (picking) ZonePick(shown, s.preferredZones, { picking = false }) { z -> onChange { it.copy(zone = z.id) } }
}

@Composable
private fun WidgetOptions(s: Settings, calendars: List<CalendarInfo>, onChange: ((Settings) -> Settings) -> Unit) {
    Section("Colors")
    Choose("Widget theme", if (s.widgetDark) "Dark" else "Light", listOf("Dark", "Light")) { i ->
        onChange { it.copy(widgetDark = i == 0, widgetText = if (i == 0) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt(), widgetBackground = if (i == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()) }
    }
    ColorRow("Text color", s.widgetText) { c -> onChange { it.copy(widgetText = c) } }
    ColorRow("Background color", s.widgetBackground) { c -> onChange { it.copy(widgetBackground = c) } }
    SliderRow("Background opacity", s.widgetOpacity, 0f..1f, 0, "${(s.widgetOpacity * 100).toInt()}%") { v -> onChange { it.copy(widgetOpacity = (v * 100).toInt() / 100f) } }
    Section("Show on widget")
    for (c in calendars) {
        val on = c.id !in s.widgetHidden
        Row(Modifier.fillMaxWidth().clickable { onChange { it.copy(widgetHidden = if (on) it.widgetHidden + c.id else it.widgetHidden - c.id) } }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = on,
                onCheckedChange = { v -> onChange { it.copy(widgetHidden = if (v) it.widgetHidden - c.id else it.widgetHidden + c.id) } },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Color(c.color)),
                modifier = Modifier.semantics { contentDescription = c.name },
            )
            Text(c.name, style = type(16, color = LocalInk.current.text))
        }
    }
}

@Composable
private fun EmojiOptions(s: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val ink = LocalInk.current
    Section("General")
    Toggle("Emoji enabled", s.emoji) { on -> onChange { it.copy(emoji = on) } }
    Section("Favorite emoji")
    val favorites = com.xern.xrncal.domain.TitleEmoji.favorites(s.emojiUse)
    if (favorites.isEmpty()) {
        Text("None yet", style = type(14, color = ink.muted), modifier = Modifier.padding(horizontal = 20.dp))
    } else {
        for (row in favorites.chunked(4)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in row) Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(ink.field), contentAlignment = Alignment.Center) { Text(e, style = type(22)) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        }
        Text("Clear", style = type(14, FontWeight.Medium, ink.accent), modifier = Modifier.padding(start = 20.dp, top = 6.dp).clickable { onChange { it.copy(emojiUse = "") } }.padding(vertical = 4.dp))
    }
}
