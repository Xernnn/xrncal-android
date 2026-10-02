package app.xrncal.android

import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Capacitor face of [NativeHttp], registered in MainActivity.
 *
 * This is a promise-based plugin rather than another method on XrncalNative
 * on purpose. XrncalNative's methods block the JS thread until they return,
 * which is what the synchronous SQLite driver needs and exactly what a network
 * call must not do: a slow CalDAV REPORT would freeze the whole UI.
 *
 * Bodies cross the bridge as base64 so ICS, XML and anything binary arrive
 * byte for byte. The TypeScript side of this contract is
 * src/android/platform/native-fetch.ts - change one, change both.
 */
@CapacitorPlugin(name = "XrncalHttp")
class XrncalHttpPlugin : Plugin() {

    private val http = NativeHttp()

    @PluginMethod
    fun request(call: PluginCall) {
        val id = call.getString("id")
        val url = call.getString("url")
        val method = call.getString("method")
        if (id == null || url == null || method == null) {
            call.reject("XrncalHttp.request needs id, url and method", "INVALID")
            return
        }

        val headers = LinkedHashMap<String, String>()
        call.getObject("headers")?.let { obj ->
            for (name in obj.keys()) obj.getString(name)?.let { headers[name] = it }
        }
        val body = call.getString("body")?.let { Base64.decode(it, Base64.NO_WRAP) }

        http.execute(
            NativeHttp.Request(id, url, method, headers, body),
            onReply = { reply ->
                val replyHeaders = JSObject()
                for ((name, value) in reply.headers) replyHeaders.put(name, value)
                call.resolve(
                    JSObject()
                        .put("status", reply.status)
                        .put("statusText", reply.statusText)
                        .put("url", reply.url)
                        .put("headers", replyHeaders)
                        .put("body", Base64.encodeToString(reply.body, Base64.NO_WRAP))
                )
            },
            onFailure = { failure ->
                call.reject(failure.message, if (failure.aborted) "ABORTED" else "NETWORK", failure)
            }
        )
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        call.getString("id")?.let { http.cancel(it) }
        call.resolve()
    }
}
