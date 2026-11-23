package com.xern.xrncal.data

import com.xern.xrncal.domain.EventView
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * A failed call: [status] 0 when the server couldn't be reached. Temporary ones are tried again on
 * their own; the others need the owner (a wrong password, a wrong address).
 */
class DavFailure(val status: Int, message: String) : IOException(message) {
    val temporary: Boolean get() = status == 0 || status == 429 || status >= 500

    fun text(): String = when {
        status == 0 -> "Server not reachable"
        status == 401 -> "Wrong name or password"
        status == 403 -> "Not allowed"
        status == 404 -> "No calendars at this address"
        status >= 500 -> "Server error $status"
        else -> "Server answered $status"
    }
}

data class DavCalendar(val href: String, val name: String, val color: Int?, val ctag: String?, val readOnly: Boolean)

data class DavItem(val href: String, val etag: String?, val ics: String? = null)

/** What changed in a collection since a sync-token: changed hrefs (with their ETags), removed ones, the new token. */
data class DavDelta(val token: String?, val changed: List<DavItem>, val removed: List<String>)

/** What the sync engine needs of a CalDAV server; [OkCalDav] over HTTP. */
interface CalDav {
    /** The calendar home, found from the address the owner typed (RFC 6764 style). */
    fun findHome(): String

    fun calendars(home: String): List<DavCalendar>

    /** Changes since [token] (null: everything); null when the server won't answer with a token. */
    fun syncCollection(calendar: String, token: String?): DavDelta?

    /** Every event resource with its ETag, no data. */
    fun etags(calendar: String): List<DavItem>

    /** The named resources with their data, in batches. */
    fun multiget(calendar: String, hrefs: List<String>): List<DavItem>

    fun get(href: String): DavItem?

    /** PUT; [etag] makes it If-Match, [create] If-None-Match: *. Returns the status and the new ETag. */
    fun put(href: String, ics: String, etag: String?, create: Boolean): Pair<Int, String?>

    fun delete(href: String, etag: String?): Int
}

/**
 * CalDAV over OkHttp: HttpURLConnection refuses PROPFIND and REPORT. Basic auth goes only to the
 * account's own host, and never from https to http; redirects are followed by hand, the same way.
 */
class OkCalDav(address: String, private val user: String, private val password: String, client: OkHttpClient? = null) : CalDav {
    val base: String = address.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }.let { if (it.endsWith("/")) it else "$it/" }
    private val baseUri = URI(base)

    init {
        val host = baseUri.host.orEmpty()
        val local = host == "127.0.0.1" || host == "10.0.2.2" || host == "localhost"
        require(baseUri.scheme == "https" || (baseUri.scheme == "http" && local)) { "The address must start with https://" }
    }

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val calendar = "text/calendar; charset=utf-8".toMediaType()

    private fun mayCarryLogin(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (baseUri.scheme == "https" && u.scheme != "https") return false
        return u.host.equals(baseUri.host, true) && (if (u.port == -1) defaultPort(u.scheme) else u.port) == (if (baseUri.port == -1) defaultPort(baseUri.scheme) else baseUri.port)
    }

    private fun defaultPort(scheme: String?) = if (scheme == "https") 443 else 80

    private class Reply(val status: Int, val body: String, val etag: String?, val location: String?, val url: String)

    private fun call(method: String, url: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap(), hops: Int = 3): Reply {
        val target = url.toHttpUrlOrNull() ?: throw DavFailure(0, "bad address $url")
        val req = Request.Builder().url(target).method(method, body).apply {
            if (mayCarryLogin(url)) header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
            for ((k, v) in headers) header(k, v)
        }.build()
        val reply = try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.let { b ->
                    // A calendar of thousands of events is a few MB of XML; far more is not an answer.
                    if (b.contentLength() > MAX_BYTES) throw DavFailure(r.code, "answer too large")
                    b.string()
                }.orEmpty()
                Reply(r.code, text, r.header("ETag"), r.header("Location"), url)
            }
        } catch (e: DavFailure) {
            throw e
        } catch (e: IOException) {
            throw DavFailure(0, e.toString())
        }
        if (reply.status in listOf(301, 302, 307, 308) && reply.location != null && hops > 0) {
            val next = URI(url).resolve(reply.location).toString()
            if (mayCarryLogin(next)) return call(method, next, body, headers, hops - 1)
        }
        if (reply.status == 401) throw DavFailure(401, "unauthorized")
        return reply
    }

    private fun propfind(url: String, depth: String, props: String): Reply =
        call("PROPFIND", url, PROPFIND.format(props).toRequestBody(xml), mapOf("Depth" to depth))

    private fun propHref(url: String, prop: String, element: String): String? {
        val r = propfind(url, "0", prop)
        if (r.status != 207) return null
        val doc = parse(r.body) ?: return null
        val el = doc.byName(element).firstOrNull() ?: return null
        val href = el.byName("href").firstOrNull()?.textContent?.trim() ?: return null
        return URI(r.url).resolve(href).toString()
    }

    override fun findHome(): String {
        if (calendarsAt(base).isNotEmpty()) return base
        val principal = propHref(base, "<D:current-user-principal/>", "current-user-principal")
            ?: propHref(URI(base).resolve("/.well-known/caldav").toString(), "<D:current-user-principal/>", "current-user-principal")
            ?: throw DavFailure(404, "no principal")
        return propHref(principal, "<C:calendar-home-set/>", "calendar-home-set") ?: throw DavFailure(404, "no calendar home")
    }

    private fun calendarsAt(url: String): List<DavCalendar> {
        val r = propfind(url, "1", CALENDAR_PROPS)
        if (r.status in listOf(403, 404, 405, 501)) return emptyList()
        if (r.status != 207) throw DavFailure(r.status, r.body.take(200))
        val doc = parse(r.body) ?: throw DavFailure(r.status, "not a multistatus")
        return doc.byName("response").mapNotNull { resp ->
            val href = resp.byName("href").firstOrNull()?.textContent?.trim() ?: return@mapNotNull null
            val ok = resp.byName("propstat").filter { ps -> ps.byName("status").firstOrNull()?.textContent?.contains(" 200") != false }
            val types = ok.flatMap { it.byName("resourcetype") }
            if (types.none { t -> t.childElements().any { it.localName == "calendar" } }) return@mapNotNull null
            // A collection that names its components and leaves out VEVENT holds tasks only.
            val comps = ok.flatMap { it.byName("comp") }.mapNotNull { it.getAttribute("name")?.uppercase() }
            if (comps.isNotEmpty() && "VEVENT" !in comps) return@mapNotNull null
            val name = ok.flatMap { it.byName("displayname") }.firstOrNull()?.textContent?.trim().orEmpty()
            val color = ok.flatMap { it.byName("calendar-color") }.firstOrNull()?.textContent?.let(EventView::hexColor)
            val ctag = ok.flatMap { it.byName("getctag") }.firstOrNull()?.textContent?.trim()
            val privileges = ok.flatMap { it.byName("current-user-privilege-set") }
            val readOnly = privileges.isNotEmpty() && privileges.none { set -> set.byName("write").isNotEmpty() || set.byName("all").isNotEmpty() || set.byName("write-content").isNotEmpty() }
            val absolute = URI(r.url).resolve(href).toString()
            DavCalendar(absolute, name.ifEmpty { absolute.trimEnd('/').substringAfterLast('/') }, color, ctag, readOnly)
        }
    }

    override fun calendars(home: String): List<DavCalendar> = calendarsAt(home)

    override fun syncCollection(calendar: String, token: String?): DavDelta? {
        val body = SYNC_COLLECTION.format(if (token.isNullOrEmpty()) "<D:sync-token/>" else "<D:sync-token>${escape(token)}</D:sync-token>")
        val r = call("REPORT", calendar, body.toRequestBody(xml), mapOf("Depth" to "1"))
        if (r.status != 207) {
            // An unknown or expired token (403/409 valid-sync-token), or no sync-collection at all.
            if (r.status in 400..499 || r.status == 501) return null
            throw DavFailure(r.status, r.body.take(200))
        }
        val doc = parse(r.body) ?: throw DavFailure(r.status, "not a multistatus")
        val changed = mutableListOf<DavItem>()
        val removed = mutableListOf<String>()
        for (resp in doc.byName("response")) {
            val href = resp.byName("href").firstOrNull()?.textContent?.trim() ?: continue
            val absolute = URI(r.url).resolve(href).toString()
            if (absolute.trimEnd('/') == calendar.trimEnd('/')) continue
            val top = resp.childElements().firstOrNull { it.localName == "status" }?.textContent.orEmpty()
            if (top.contains(" 404")) {
                removed += absolute
                continue
            }
            val etag = resp.byName("propstat").filter { it.byName("status").firstOrNull()?.textContent?.contains(" 200") != false }
                .flatMap { it.byName("getetag") }.firstOrNull()?.textContent?.trim()
            if (etag != null || !absolute.endsWith("/")) changed += DavItem(absolute, etag)
        }
        val newToken = doc.documentElement.childElements().firstOrNull { it.localName == "sync-token" }?.textContent?.trim()
        return DavDelta(newToken, changed, removed)
    }

    override fun etags(calendar: String): List<DavItem> {
        val r = call("REPORT", calendar, QUERY.toRequestBody(xml), mapOf("Depth" to "1"))
        if (r.status != 207) throw DavFailure(r.status, r.body.take(200))
        // A 2xx that isn't a multistatus (a captive portal's page) lists nothing, and must not
        // read as every event deleted on the server.
        return items(r) ?: throw DavFailure(r.status, "not a multistatus")
    }

    override fun multiget(calendar: String, hrefs: List<String>): List<DavItem> {
        val out = mutableListOf<DavItem>()
        val origin = URI(calendar).let { "${it.scheme}://${it.rawAuthority}" }
        for (batch in hrefs.chunked(MULTIGET_BATCH)) {
            val refs = batch.joinToString("\n") { h ->
                val path = if (h.startsWith(origin)) URI(h).rawPath else h
                "  <D:href>${escape(path)}</D:href>"
            }
            val r = call("REPORT", calendar, MULTIGET.format(refs).toRequestBody(xml), mapOf("Depth" to "1"))
            if (r.status != 207) {
                if (r.status in listOf(400, 403, 405, 415, 501)) return batch.mapNotNull { get(it) }
                throw DavFailure(r.status, r.body.take(200))
            }
            out += (items(r) ?: throw DavFailure(r.status, "not a multistatus")).filter { it.ics != null }
        }
        return out
    }

    override fun get(href: String): DavItem? {
        val r = call("GET", href)
        if (r.status == 404 || r.status == 410) return null
        if (r.status !in 200..299) throw DavFailure(r.status, r.body.take(200))
        return DavItem(href, r.etag, r.body)
    }

    override fun put(href: String, ics: String, etag: String?, create: Boolean): Pair<Int, String?> {
        val headers = buildMap {
            if (create) put("If-None-Match", "*") else if (etag != null) put("If-Match", etag)
        }
        val r = call("PUT", href, ics.toRequestBody(calendar), headers)
        return r.status to r.etag
    }

    override fun delete(href: String, etag: String?): Int = call("DELETE", href, null, if (etag != null) mapOf("If-Match" to etag) else emptyMap()).status

    private fun items(r: Reply): List<DavItem>? {
        val doc = parse(r.body) ?: return null
        return doc.byName("response").mapNotNull { resp ->
            val href = resp.byName("href").firstOrNull()?.textContent?.trim() ?: return@mapNotNull null
            val ok = resp.byName("propstat").filter { it.byName("status").firstOrNull()?.textContent?.contains(" 200") != false }
            val absolute = URI(r.url).resolve(href).toString()
            if (absolute.endsWith("/")) return@mapNotNull null
            DavItem(absolute, ok.flatMap { it.byName("getetag") }.firstOrNull()?.textContent?.trim(), ok.flatMap { it.byName("calendar-data") }.firstOrNull()?.textContent)
        }
    }

    private class Doc(val documentElement: Element) {
        fun byName(local: String): List<Element> = documentElement.byName(local)
    }

    /** A multistatus, or null for anything else. A DOCTYPE is refused outright (no entities from a server). */
    private fun parse(body: String): Doc? {
        if (body.contains("<!DOCTYPE", ignoreCase = true)) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isExpandEntityReferences = false }
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(InputSource(body.reader())) }.getOrNull() ?: return null
        if (doc.documentElement?.localName != "multistatus") return null
        return Doc(doc.documentElement)
    }

    companion object {
        const val MULTIGET_BATCH = 100
        const val MAX_BYTES = 200L * 1024 * 1024

        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/" xmlns:I="http://apple.com/ns/ical/">
  <D:prop>
    %s
  </D:prop>
</D:propfind>"""

        // No sync-token here: Radicale works it out from every item, which costs seconds on a
        // large calendar (the laptop app measured it); the ctag decides whether to look further.
        private const val CALENDAR_PROPS = "<D:resourcetype/><D:displayname/><I:calendar-color/><CS:getctag/><D:current-user-privilege-set/><C:supported-calendar-component-set/>"

        private const val SYNC_COLLECTION = """<?xml version="1.0" encoding="utf-8" ?>
<D:sync-collection xmlns:D="DAV:">
  %s
  <D:sync-level>1</D:sync-level>
  <D:prop>
    <D:getetag/>
  </D:prop>
</D:sync-collection>"""

        private const val QUERY = """<?xml version="1.0" encoding="utf-8" ?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <D:getetag/>
  </D:prop>
  <C:filter>
    <C:comp-filter name="VCALENDAR">
      <C:comp-filter name="VEVENT"/>
    </C:comp-filter>
  </C:filter>
</C:calendar-query>"""

        private const val MULTIGET = """<?xml version="1.0" encoding="utf-8" ?>
<C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
%s
</C:calendar-multiget>"""

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

private fun Element.childElements(): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
    return out
}

/** Descendants by local name, whatever their namespace prefix. */
private fun Element.byName(local: String): List<Element> {
    val nodes = getElementsByTagNameNS("*", local)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}
