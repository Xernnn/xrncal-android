package app.xrncal.android

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Provider HTTP for the requests CapacitorHttp cannot make.
 *
 * CapacitorHttp sends every non-GET request through Android's
 * `HttpURLConnection`, which rejects any method outside a fixed list -
 * PROPFIND and REPORT included, so CalDAV could neither discover nor pull -
 * and never sees the request's AbortSignal, so a stalled push could not be
 * cancelled. OkHttp takes any method token and cancels a call on request.
 *
 * Redirects are deliberately *not* followed here. OkHttp turns a redirected
 * REPORT or PUT into a GET, where fetch keeps the method; the JS side
 * (src/android/platform/native-fetch.ts) follows them with fetch's rules.
 *
 * No Android APIs on purpose, so NativeHttpTest can drive it on the JVM
 * against MockWebServer. XrncalHttpPlugin is the Capacitor-facing wrapper.
 */
class NativeHttp(private val client: OkHttpClient = defaultClient()) {

    class Request(
        val id: String,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    class Reply(
        val status: Int,
        val statusText: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    /** `aborted` separates a cancel from a network failure; fetch reports them differently. */
    class Failure(val aborted: Boolean, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val inFlight = ConcurrentHashMap<String, Call>()

    companion object {
        /**
         * Backstops only. The JS side bounds every request through
         * fetchWithTimeout and cancels the call when that fires; these stop a
         * request whose caller set no deadline from holding a thread forever.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Starts the request and returns at once; exactly one callback fires, on an OkHttp thread. */
    fun execute(request: Request, onReply: (Reply) -> Unit, onFailure: (Failure) -> Unit) {
        val call = try {
            client.newCall(build(request))
        } catch (e: IllegalArgumentException) {
            // A malformed URL, header or method/body combination: report it
            // like any other failed request instead of throwing into Capacitor.
            onFailure(Failure(false, e.message ?: "invalid request", e))
            return
        }
        inFlight[request.id] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inFlight.remove(request.id)
                onFailure(Failure(call.isCanceled(), describe(e), e))
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { toReply(it) }
                } catch (e: IOException) {
                    // The body is read here, so a stall or cancel mid-body lands
                    // in this branch rather than in onFailure.
                    inFlight.remove(request.id)
                    onFailure(Failure(call.isCanceled(), describe(e), e))
                    return
                }
                inFlight.remove(request.id)
                onReply(reply)
            }
        })
    }

    /** Cancels an in-flight request; a no-op once it has finished. */
    fun cancel(id: String) {
        inFlight.remove(id)?.cancel()
    }

    private fun build(request: Request): okhttp3.Request {
        val headers = Headers.Builder()
        var contentType: String? = null
        for ((name, value) in request.headers) {
            headers.add(name, value)
            if (name.equals("Content-Type", ignoreCase = true)) contentType = value
        }

        // GET and HEAD may not carry a body. Every other method gets one, empty
        // if need be, because OkHttp refuses a bodiless POST, PUT, PATCH or
        // REPORT outright.
        val body = when (request.method) {
            "GET", "HEAD" -> null
            else -> (request.body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
        }

        return okhttp3.Request.Builder()
            .url(request.url)
            .headers(headers.build())
            .method(request.method, body)
            .build()
    }

    private fun toReply(response: Response): Reply {
        // fetch's Headers joins repeated fields with ", ", so this loses nothing
        // the JS side could have kept.
        val headers = LinkedHashMap<String, String>()
        for (name in response.headers.names()) {
            headers[name] = response.headers.values(name).joinToString(", ")
        }
        return Reply(
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            headers = headers,
            body = response.body?.bytes() ?: ByteArray(0)
        )
    }

    private fun describe(e: IOException): String = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
}
