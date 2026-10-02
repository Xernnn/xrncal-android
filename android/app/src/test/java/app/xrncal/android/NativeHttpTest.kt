package app.xrncal.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Pins the two things CapacitorHttp got wrong and NativeHttp exists for:
 * WebDAV methods reach the server, and a stalled request can be cancelled.
 */
class NativeHttpTest {

    private lateinit var server: MockWebServer
    private val http = NativeHttp()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private sealed class Outcome {
        class Ok(val reply: NativeHttp.Reply) : Outcome()
        class Failed(val failure: NativeHttp.Failure) : Outcome()
    }

    private fun send(request: NativeHttp.Request): CompletableFuture<Outcome> {
        val done = CompletableFuture<Outcome>()
        http.execute(
            request,
            onReply = { done.complete(Outcome.Ok(it)) },
            onFailure = { done.complete(Outcome.Failed(it)) }
        )
        return done
    }

    private fun reply(outcome: Outcome): NativeHttp.Reply =
        (outcome as? Outcome.Ok)?.reply
            ?: throw AssertionError("expected a reply, got ${(outcome as Outcome.Failed).failure}")

    private fun request(
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        id: String = "1"
    ) = NativeHttp.Request(id, server.url("/dav/").toString(), method, headers, body?.toByteArray())

    @Test
    fun `PROPFIND reaches the server with its body and headers`() {
        val multistatus = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"/>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        val result = reply(
            send(
                request(
                    "PROPFIND",
                    body = "<D:propfind/>",
                    headers = mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8")
                )
            ).get(5, TimeUnit.SECONDS)
        )

        val recorded = server.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("1", recorded.getHeader("Depth"))
        assertEquals("application/xml; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("<D:propfind/>", recorded.body.readUtf8())
        assertEquals(207, result.status)
        assertEquals(multistatus, String(result.body))
    }

    @Test
    fun `REPORT is sent as REPORT`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus/>"))

        reply(send(request("REPORT", body = "<C:calendar-query/>")).get(5, TimeUnit.SECONDS))

        assertEquals("REPORT", server.takeRequest().method)
    }

    @Test
    fun `a bodiless POST is sent rather than refused`() {
        server.enqueue(MockResponse().setResponseCode(200))

        reply(send(request("POST")).get(5, TimeUnit.SECONDS))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun `redirects are returned, not followed`() {
        // OkHttp would replay a redirected REPORT as GET; the JS side follows
        // redirects instead, with fetch's method rules.
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/elsewhere/"))

        val result = reply(send(request("REPORT", body = "<q/>")).get(5, TimeUnit.SECONDS))

        assertEquals(301, result.status)
        assertEquals("/elsewhere/", result.headers["Location"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `binary bodies round-trip byte for byte`() {
        val bytes = ByteArray(256) { it.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        val result = reply(send(request("PUT", body = null)).get(5, TimeUnit.SECONDS))

        assertArrayEquals(bytes, result.body)
    }

    @Test
    fun `repeated response headers are joined the way fetch joins them`() {
        server.enqueue(MockResponse().addHeader("DAV", "1").addHeader("DAV", "calendar-access"))

        val result = reply(send(request("OPTIONS")).get(5, TimeUnit.SECONDS))

        assertEquals("1, calendar-access", result.headers["DAV"])
    }

    @Test
    fun `cancel ends a stalled request and reports it as aborted`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val pending = send(request("PUT", body = "x", id = "stalled"))
        Thread.sleep(200)
        assertFalse("request should still be waiting on the server", pending.isDone)

        http.cancel("stalled")

        val outcome = pending.get(5, TimeUnit.SECONDS)
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).failure.aborted)
    }

    @Test
    fun `a connection failure is a failure, not an abort`() {
        // A port that was free a moment ago: nothing is listening on it.
        val port = java.net.ServerSocket(0).use { it.localPort }

        val outcome = send(NativeHttp.Request("1", "http://127.0.0.1:$port/", "DELETE", emptyMap(), null))
            .get(20, TimeUnit.SECONDS)

        assertTrue(outcome is Outcome.Failed)
        assertFalse((outcome as Outcome.Failed).failure.aborted)
    }

    @Test
    fun `an invalid request fails through the callback instead of throwing`() {
        val outcome = send(NativeHttp.Request("1", "not a url", "POST", emptyMap(), null))
            .get(5, TimeUnit.SECONDS)

        assertNotNull((outcome as Outcome.Failed).failure.message)
    }
}
