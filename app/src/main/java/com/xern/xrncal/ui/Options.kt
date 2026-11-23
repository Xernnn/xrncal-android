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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.ThemeMode
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
    ColorRow("Signal colour", s.signal) { c -> onChange { it.copy(signal = c) } }
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
