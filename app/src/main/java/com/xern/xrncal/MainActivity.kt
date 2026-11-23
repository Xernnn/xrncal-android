package com.xern.xrncal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xern.xrncal.ui.App
import com.xern.xrncal.ui.XrnTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as XrnCalApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph.start()
        setContent { XrnTheme { App(graph) } }
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
