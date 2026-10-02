import { registerPlugin } from '@capacitor/core'
import { toBase64, fromBase64 } from '../shims/buffer-polyfill'

/**
 * `fetch` for the provider requests CapacitorHttp cannot make.
 *
 * CapacitorHttp is what lets provider calls leave the WebView at all - CalDAV
 * servers send no CORS headers - but it hands every non-GET request to
 * Android's `HttpURLConnection`, which
 *
 *   - rejects any method outside a fixed list, PROPFIND and REPORT included,
 *     so CalDAV could neither discover calendars nor pull events; and
 *   - never sees the request's AbortSignal, so a stalled push or token refresh
 *     could not be cancelled.
 *
 * Everything but GET and HEAD therefore goes to the XrncalHttp plugin (OkHttp;
 * android/app/src/main/java/app/xrncal/android/NativeHttp.kt), which takes any
 * method and cancels the native call on abort. GET and HEAD stay on
 * CapacitorHttp, which handles both correctly.
 *
 * Redirects are followed here rather than natively, because OkHttp replays a
 * redirected REPORT or PUT as a GET where fetch keeps the method.
 *
 * One difference from desktop: the reply arrives whole, so a caller's deadline
 * (fetchWithTimeout) covers reading the body too, not just the headers.
 */

/** The plugin's request, exactly as XrncalHttpPlugin.kt reads it. */
export interface NativeHttpRequest {
  id: string
  url: string
  method: string
  headers: Record<string, string>
  /** Base64; absent when the request has no body. */
  body?: string
}

export interface NativeHttpReply {
  status: number
  statusText: string
  url: string
  headers: Record<string, string>
  /** Base64. */
  body: string
}

export interface NativeHttpTransport {
  request(options: NativeHttpRequest): Promise<NativeHttpReply>
  cancel(options: { id: string }): Promise<void>
}

const MAX_REDIRECTS = 20
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308])
/** Statuses `new Response()` refuses a body for. */
const NULL_BODY_STATUSES = new Set([101, 103, 204, 205, 304])

/** Whether a request goes to the native transport rather than CapacitorHttp. */
export function routesNatively(method: string, url: string, appOrigin: string): boolean {
  if (method === 'GET' || method === 'HEAD') return false
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return false
  }
  // The app's own origin is served from the APK's assets, not the network.
  return (parsed.protocol === 'https:' || parsed.protocol === 'http:') && parsed.origin !== appOrigin
}

export function createNativeFetch(
  transport: NativeHttpTransport,
  fallback: typeof fetch,
  appOrigin: string
): typeof fetch {
  let nextId = 0

  return async function nativeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const request = new Request(input, init)
    if (!routesNatively(request.method, request.url, appOrigin)) return fallback(input, init)

    const signal = request.signal
    let method = request.method
    let url = request.url
    const headers: Record<string, string> = Object.fromEntries(request.headers)
    let body: string | undefined =
      request.body === null ? undefined : toBase64(new Uint8Array(await request.arrayBuffer()))

    for (let redirects = 0; ; redirects++) {
      const reply = await send(transport, { id: String(++nextId), url, method, headers, body }, signal)

      const location = headerValue(reply.headers, 'location')
      if (!REDIRECT_STATUSES.has(reply.status) || location === undefined || request.redirect === 'manual') {
        return toResponse(reply)
      }
      if (request.redirect === 'error') {
        throw new TypeError(`Redirect from ${url} refused: the request set redirect: 'error'`)
      }
      if (redirects >= MAX_REDIRECTS) {
        throw new TypeError(`Too many redirects starting from ${request.url}`)
      }

      // fetch's rules: 303 becomes a GET, and so does a POST on 301/302.
      // Every other redirect keeps its method and body - which is the whole
      // point of following them here.
      if ((reply.status === 303 && method !== 'HEAD') || ((reply.status === 301 || reply.status === 302) && method === 'POST')) {
        method = 'GET'
        body = undefined
        for (const name of Object.keys(headers)) {
          if (name.startsWith('content-')) delete headers[name]
        }
      }

      const next = new URL(location, url)
      // Credentials are not handed to another origin, as Node's fetch and the
      // browser both behave on desktop.
      if (next.origin !== new URL(url).origin) delete headers.authorization
      url = next.href
    }
  }
}

function send(
  transport: NativeHttpTransport,
  request: NativeHttpRequest,
  signal: AbortSignal
): Promise<NativeHttpReply> {
  if (signal.aborted) return Promise.reject(abortReason(signal))

  return new Promise((resolve, reject) => {
    const onAbort = (): void => {
      // Settle now; the native call is cancelled in the background.
      transport.cancel({ id: request.id }).catch(() => {})
      reject(abortReason(signal))
    }
    signal.addEventListener('abort', onAbort, { once: true })

    transport
      .request(request)
      .then(resolve, (err) => reject(toFetchError(err, signal)))
      .finally(() => signal.removeEventListener('abort', onAbort))
  })
}

function toResponse(reply: NativeHttpReply): Response {
  const headers = new Headers()
  for (const [name, value] of Object.entries(reply.headers)) {
    try {
      headers.append(name, value)
    } catch {
      // A value fetch's Headers cannot hold (non-Latin-1, say). Dropping the
      // one header beats failing a response whose body is fine.
    }
  }
  const body = NULL_BODY_STATUSES.has(reply.status) ? null : fromBase64(reply.body)
  const response = new Response(body, { status: reply.status, statusText: reply.statusText, headers })
  // `url` is a read-only getter on Response; CapacitorHttp sets it the same way.
  Object.defineProperty(response, 'url', { value: reply.url })
  return response
}

function headerValue(headers: Record<string, string>, name: string): string | undefined {
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === name) return value
  }
  return undefined
}

/** What fetch rejects with when its signal fires: the signal's reason. */
function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError')
}

/** fetch reports a network failure as a TypeError, and an abort as an AbortError. */
function toFetchError(err: unknown, signal: AbortSignal): unknown {
  if (signal.aborted) return abortReason(signal)
  // Capacitor rejects with an Error carrying the `code` XrncalHttpPlugin passed.
  const { code, message } = (err ?? {}) as { code?: string; message?: string }
  if (code === 'ABORTED') return new DOMException('The operation was aborted.', 'AbortError')
  return new TypeError(`Network request failed: ${message ?? String(err)}`)
}

/**
 * Wraps the global fetch. Capacitor's bridge script patches `window.fetch`
 * before the bundle runs, so the fallback captured here is CapacitorHttp's;
 * call this before anything issues a request.
 */
export function installNativeFetch(): void {
  const transport = registerPlugin<NativeHttpTransport>('XrncalHttp')
  const fallback = window.fetch.bind(window)
  window.fetch = createNativeFetch(transport, fallback, window.location.origin)
}
