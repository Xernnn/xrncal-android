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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.xern.xrncal.data.ThemeMode
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
        enableEdgeToEdge()
        graph.start()
        setContent {
            val s by graph.settings.flow.collectAsState()
            val dark = when (s.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The status bar's icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear), if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear))
            }
            val wallpaper = rememberWallpaper(s.wallpaper)
            XrnTheme(dark = dark, wallpaper = s.wallpaper != Wallpapers.NONE, signal = Color(s.signalFor(dark))) {
                CompositionLocalProvider(LocalBackdrop provides { Backdrop(wallpaper, s.overlay, s.blur, dark) }) { App(graph) }
            }
        }
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

    /** A reminder's notification was tapped: its event. */
    private fun opened(intent: Intent?) {
        val id = intent?.getStringExtra(OPEN) ?: return
        graph.open(id, intent.getLongExtra(START, -1).takeIf { it >= 0 })
    }

    override fun onResume() {
        super.onResume()
        graph.syncSoon(0)
    }

    companion object {
        const val OPEN = "open"
        const val START = "start"
    }
}
