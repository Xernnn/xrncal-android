package com.xern.xrncal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.xern.xrncal.data.ThemeMode
import com.xern.xrncal.domain.TimeLink
import com.xern.xrncal.ui.App
import com.xern.xrncal.ui.Backdrop
import com.xern.xrncal.ui.LocalBackdrop
import com.xern.xrncal.ui.Wallpapers
import com.xern.xrncal.ui.XrnTheme
import com.xern.xrncal.ui.rememberWallpaper

class MainActivity : ComponentActivity() {
    private val graph get() = (application as XrnCalApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph.start()
        setThemedContent(graph) { App(graph) }
        if (savedInstanceState == null) opened(intent)
        // Reminders are notifications: Android 13 and later ask once.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /**
     * A reminder's notification was tapped: its event. Or another app's time link
     * (`content://com.android.calendar/time/<millis>`): the Day view on its date.
     */
    private fun opened(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { TimeLink.day(it.toString(), graph.settings.value.zoneId()) }?.let(graph::showDay)
            return
        }
        val id = intent?.getStringExtra(OPEN) ?: return
        graph.open(id, intent.getLongExtra(START, -1).takeIf { it >= 0 })
    }

    override fun onResume() {
        super.onResume()
        graph.syncSoon(0)
        com.xern.xrncal.work.OemAutostart.check(this)
    }

    companion object {
        const val OPEN = "open"
        const val START = "start"
    }
}

/**
 * [content] in the app's theme and wallpaper as Options set them, edge to edge, the status bar's
 * icons following the app's theme rather than the phone's.
 */
fun ComponentActivity.setThemedContent(graph: Graph, content: @Composable () -> Unit) {
    enableEdgeToEdge()
    setContent {
        val s by graph.settings.flow.collectAsState()
        val dark = when (s.theme) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        LaunchedEffect(dark) {
            val clear = android.graphics.Color.TRANSPARENT
            enableEdgeToEdge(if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear), if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear))
        }
        val wallpaper = rememberWallpaper(s.wallpaper)
        XrnTheme(dark = dark, wallpaper = s.wallpaper != Wallpapers.NONE, signal = Color(s.signalFor(dark))) {
            CompositionLocalProvider(LocalBackdrop provides { Backdrop(wallpaper, s.overlay, s.blur, dark) }, content = content)
        }
    }
}
