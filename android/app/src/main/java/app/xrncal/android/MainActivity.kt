package app.xrncal.android

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.BridgeActivity
import org.json.JSONObject

/**
 * Attaches the synchronous `XrncalNative` bridge and feeds the WebView the
 * window insets it cannot work out for itself.
 *
 * Bridge timing matters. `addJavascriptInterface` only affects frames loaded
 * *after* the call, so it has to happen before the bundle's JS runs.
 * `super.onCreate()` creates the Bridge and queues the page load on the UI
 * thread's message loop; because this call is synchronous on that same thread
 * and returns before the loop gets to evaluate the page, the interface is in
 * place by the time `main.tsx` looks for it. `boot()` on the JS side still
 * waits for it and fails loudly instead of hanging, in case that ever changes.
 */
class MainActivity : BridgeActivity() {

    /**
     * The last insets payload pushed to the page. XrncalNative.windowInsets()
     * hands it over when the page asks, because the first push usually lands
     * before the page has installed its hook - see publishWindowInsets().
     */
    @Volatile
    var latestInsets: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // App-local plugins are not discovered from capacitor.plugins.json;
        // they have to be registered before super.onCreate() builds the Bridge.
        registerPlugin(XrncalHttpPlugin::class.java)
        super.onCreate(savedInstanceState)
        bridge.webView.addJavascriptInterface(XrncalNative(this), "XrncalNative")
        publishWindowInsets()
    }

    /**
     * Push the real system-bar insets into the page as CSS pixels.
     *
     * A WebView reports `env(safe-area-inset-*)` for display cutouts only - it
     * is zero for the status bar and the gesture pill - so an edge-to-edge app
     * that trusts env() draws its bottom navigation underneath the pill. The
     * listener also fires on rotation and when the bars change, which is why
     * this is a subscription rather than a one-shot read.
     */
    private fun publishWindowInsets() {
        val root = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

            val payload = JSONObject()
                .put("top", bars.top / density)
                .put("bottom", bars.bottom / density)
                .put("left", bars.left / density)
                .put("right", bars.right / density)

            // On a cold start this first dispatch happens on the first layout,
            // while the bundle is still loading, so the hook usually is not
            // there yet and the call is dropped - and nothing re-dispatches
            // until the insets change (a rotation). Recording the payload
            // *before* pushing it lets the page pull it once the hook exists.
            latestInsets = payload.toString()
            bridge.webView.evaluateJavascript(
                "window.__xrncalInsets && window.__xrncalInsets($payload)",
                null
            )

            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
