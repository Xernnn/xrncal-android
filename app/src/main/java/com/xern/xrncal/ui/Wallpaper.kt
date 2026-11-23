package com.xern.xrncal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xern.xrncal.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures behind the views (docs/spec.md, phase 2 item 1): the laptop's four presets, which
 * ship with the app (Unsplash photos under the Unsplash License, cropped to 1600x900 WebP as the
 * laptop's wallpapers.ts notes them), or one imported from the gallery. The setting is
 * `preset:<key>`, `file:<name>` or `none`.
 */
object Wallpapers {
    data class Preset(val key: String, val name: String, @DrawableRes val res: Int) {
        val setting: String get() = "preset:$key"
    }

    val PRESETS = listOf(
        Preset("mistyForest", "Misty Forest", R.drawable.wallpaper_misty_forest),
        Preset("purpleSunset", "Purple Sunset", R.drawable.wallpaper_purple_sunset),
        Preset("cyberCity", "Cyber City", R.drawable.wallpaper_cyber_city),
        Preset("cosmicNight", "Cosmic Night", R.drawable.wallpaper_cosmic_night),
    )

    const val NONE = "none"

    /** The longest side an imported picture is kept at. */
    const val MAX_SIDE = 1600

    fun preset(setting: String): Preset? =
        if (setting.startsWith("preset:")) PRESETS.firstOrNull { it.key == setting.removePrefix("preset:") } else null

    private fun dir(context: Context) = File(context.filesDir, "wallpapers")

    fun file(context: Context, setting: String): File? =
        if (setting.startsWith("file:")) File(dir(context), setting.removePrefix("file:")).takeIf { it.isFile } else null

    /** The picture [setting] names, decoded at most [maxSide] across, or null for none. */
    fun decode(context: Context, setting: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val opts = BitmapFactory.Options()
        val preset = preset(setting)
        val file = file(context, setting)
        fun sample(w: Int, h: Int) = generateSequence(1) { it * 2 }.first { maxOf(w, h) / it <= maxSide }
        return when {
            preset != null -> {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeResource(context.resources, preset.res, opts)
                BitmapFactory.decodeResource(context.resources, preset.res, BitmapFactory.Options().apply { inSampleSize = sample(opts.outWidth, opts.outHeight) })
            }
            file != null -> {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, opts)
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample(opts.outWidth, opts.outHeight) })
            }
            else -> null
        }
    }

    /**
     * A picture from Android's photo picker (which needs no permission) kept as the wallpaper: scaled
     * down to [MAX_SIDE] and saved in the app's own files, the one before it removed. Returns the
     * setting, or null when it couldn't be read.
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
        if (bounds.outWidth <= 0) return@withContext null
        val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= MAX_SIDE * 2 }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@withContext null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        val dir = dir(context).apply { mkdirs() }
        val name = "wallpaper-${System.currentTimeMillis()}.jpg"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
        "file:$name"
    }
}

/** The wallpaper [setting] names, decoded off the main thread; null until then, and for none. */
@Composable
fun rememberWallpaper(setting: String, maxSide: Int = Wallpapers.MAX_SIDE): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, setting, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { Wallpapers.decode(context, setting, maxSide)?.asImageBitmap() }.getOrNull() }
    }
    return image
}

/**
 * The picture behind the views, cropped to fill, blurred by [blur] (Android 12 and later, which is
 * every phone this app runs on), under a black overlay ([overlay] dark; white in the light theme).
 */
@Composable
fun Backdrop(image: ImageBitmap?, overlay: Float, blur: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val base = if (dark) Color.Black else Color.White
    Box(modifier.fillMaxSize().background(if (image == null) LocalInk.current.screen else base)) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (blur > 0) Modifier.blur(blur.dp) else Modifier))
            Box(Modifier.fillMaxSize().background(base.copy(alpha = overlay.coerceIn(0f, 1f))))
        }
    }
}

/** Draws the backdrop again under a full screen that shows it (search), as the root does. */
val LocalBackdrop = compositionLocalOf<@Composable () -> Unit> { {} }
