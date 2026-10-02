import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  createNativeFetch,
  routesNatively,
  type NativeHttpReply,
  type NativeHttpRequest,
  type NativeHttpTransport
} from '../src/android/platform/native-fetch'
import { fetchWithTimeout, RequestTimeoutError } from '../src/main/sync/http'

/**
 * The fetch the Android build installs over CapacitorHttp's, driven against a
 * stub of the XrncalHttp plugin. The plugin itself (OkHttp) is covered by
 * NativeHttpTest.kt; this pins the TypeScript half of the contract.
 */

const APP_ORIGIN = 'https://localhost'
const DAV = 'https://dav.example/calendars/me/'

const encode = (s: string): string => Buffer.from(s).toString('base64')
const decode = (b64: string | undefined): string => Buffer.from(b64 ?? '', 'base64').toString()

function reply(partial: Partial<NativeHttpReply> = {}): NativeHttpReply {
  return { status: 200, statusText: 'OK', url: DAV, headers: {}, body: '', ...partial }
}

type Step = NativeHttpReply | (() => Promise<NativeHttpReply>)

function fakePlugin(...steps: Step[]) {
  const requests: NativeHttpRequest[] = []
  const cancelled: string[] = []
  const transport: NativeHttpTransport = {
    request: vi.fn(async (req: NativeHttpRequest) => {
      requests.push(req)
      const step = steps.shift()
      if (!step) throw new Error('no reply queued')
      return typeof step === 'function' ? step() : step
    }),
    cancel: vi.fn(async ({ id }: { id: string }) => {
      cancelled.push(id)
    })
  }
  return { transport, requests, cancelled }
}

const never = (): Promise<NativeHttpReply> => new Promise(() => {})

describe('routesNatively', () => {
  it('leaves GET and HEAD on CapacitorHttp', () => {
    expect(routesNatively('GET', DAV, APP_ORIGIN)).toBe(false)
    expect(routesNatively('HEAD', DAV, APP_ORIGIN)).toBe(false)
  })

  it('takes every other method to a remote server', () => {
    for (const method of ['PROPFIND', 'REPORT', 'PUT', 'POST', 'PATCH', 'DELETE']) {
      expect(routesNatively(method, DAV, APP_ORIGIN)).toBe(true)
    }
  })

  it("never takes the app's own origin or a non-http URL", () => {
    expect(routesNatively('POST', 'https://localhost/_capacitor_file_/x', APP_ORIGIN)).toBe(false)
    expect(routesNatively('POST', 'data:text/plain,x', APP_ORIGIN)).toBe(false)
  })
})

describe('createNativeFetch', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('hands GET to the fallback untouched', async () => {
    const { transport } = fakePlugin()
    const fallback = vi.fn(async () => new Response('from capacitor'))
    const nativeFetch = createNativeFetch(transport, fallback as typeof fetch, APP_ORIGIN)

    const init = { headers: { Authorization: 'Bearer t' } }
    const res = await nativeFetch(DAV, init)

    expect(fallback).toHaveBeenCalledWith(DAV, init)
    expect(transport.request).not.toHaveBeenCalled()
    expect(await res.text()).toBe('from capacitor')
  })

  it('sends PROPFIND with its headers and body, and builds the Response', async () => {
    const xml = '<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"/>'
    const { transport, requests } = fakePlugin(
      reply({ status: 207, statusText: 'Multi-Status', headers: { 'Content-Type': 'application/xml' }, body: encode(xml) })
    )
    const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

    const res = await nativeFetch(DAV, {
      method: 'PROPFIND',
      headers: { Authorization: 'Basic abc', Depth: '1', 'Content-Type': 'application/xml; charset=utf-8' },
      body: '<D:propfind/>'
    })

    expect(requests).toHaveLength(1)
    expect(requests[0].method).toBe('PROPFIND')
    expect(requests[0].url).toBe(DAV)
    expect(requests[0].headers).toMatchObject({
      authorization: 'Basic abc',
      depth: '1',
      'content-type': 'application/xml; charset=utf-8'
    })
    expect(decode(requests[0].body)).toBe('<D:propfind/>')

    expect(res.status).toBe(207)
    expect(res.ok).toBe(true)
    expect(res.statusText).toBe('Multi-Status')
    expect(res.url).toBe(DAV)
    expect(res.headers.get('content-type')).toBe('application/xml')
    expect(await res.text()).toBe(xml)
  })

  it('sends no body for a bodiless DELETE', async () => {
    const { transport, requests } = fakePlugin(reply({ status: 204, statusText: 'No Content' }))
    const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

    const res = await nativeFetch(`${DAV}event.ics`, { method: 'DELETE' })

    expect(requests[0].body).toBeUndefined()
    // new Response() throws if a 204 is given a body, even an empty one.
    expect(res.status).toBe(204)
  })

  it('drops a header value fetch cannot hold instead of failing the response', async () => {
    const { transport } = fakePlugin(reply({ headers: { 'X-Name': 'café ☃', ETag: '"1"' }, body: encode('ok') }))
    const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

    const res = await nativeFetch(DAV, { method: 'PUT', body: 'x' })

    expect(res.headers.get('etag')).toBe('"1"')
    expect(await res.text()).toBe('ok')
  })

  it('reports a network failure as a TypeError, the way fetch does', async () => {
    const { transport } = fakePlugin(() => Promise.reject(Object.assign(new Error('UnknownHostException'), { code: 'NETWORK' })))
    const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

    await expect(nativeFetch(DAV, { method: 'REPORT', body: '<q/>' })).rejects.toBeInstanceOf(TypeError)
  })

  describe('abort', () => {
    it('rejects with an AbortError and cancels the native call', async () => {
      const { transport, cancelled, requests } = fakePlugin(never)
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)
      const controller = new AbortController()

      const pending = nativeFetch(DAV, { method: 'PUT', body: 'x', signal: controller.signal })
      await vi.waitFor(() => expect(requests).toHaveLength(1))
      controller.abort()

      await expect(pending).rejects.toHaveProperty('name', 'AbortError')
      expect(cancelled).toEqual([requests[0].id])
    })

    it('does not start a request whose signal is already aborted', async () => {
      const { transport } = fakePlugin(reply())
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      await expect(
        nativeFetch(DAV, { method: 'POST', body: 'x', signal: AbortSignal.abort() })
      ).rejects.toHaveProperty('name', 'AbortError')
      expect(transport.request).not.toHaveBeenCalled()
    })

    // The bug this module exists for: under CapacitorHttp a stalled PUT kept
    // fetchWithTimeout - and with it SyncWorker.isSyncing - waiting forever.
    it('lets fetchWithTimeout time out a stalled push and cancel it natively', async () => {
      const { transport, cancelled, requests } = fakePlugin(never)
      vi.stubGlobal('fetch', createNativeFetch(transport, fetch, APP_ORIGIN))

      await expect(fetchWithTimeout(`${DAV}e.ics`, { method: 'PUT', body: 'BEGIN:VCALENDAR' }, 20)).rejects.toBeInstanceOf(
        RequestTimeoutError
      )
      expect(cancelled).toEqual([requests[0].id])
    })
  })

  describe('redirects', () => {
    it('keeps REPORT and its body across a 301', async () => {
      const { transport, requests } = fakePlugin(
        reply({ status: 301, headers: { Location: '/dav/calendars/me/' } }),
        reply({ status: 207, body: encode('<multistatus/>') })
      )
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      const res = await nativeFetch(DAV, { method: 'REPORT', headers: { Authorization: 'Basic abc' }, body: '<q/>' })

      expect(requests.map((r) => [r.method, r.url])).toEqual([
        ['REPORT', DAV],
        ['REPORT', 'https://dav.example/dav/calendars/me/']
      ])
      expect(decode(requests[1].body)).toBe('<q/>')
      expect(requests[1].headers.authorization).toBe('Basic abc')
      expect(res.status).toBe(207)
    })

    it('turns a POST into a bodiless GET on 303', async () => {
      const { transport, requests } = fakePlugin(
        reply({ status: 303, headers: { Location: 'https://dav.example/done' } }),
        reply({ status: 200 })
      )
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      await nativeFetch(DAV, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'x' })

      expect(requests[1].method).toBe('GET')
      expect(requests[1].body).toBeUndefined()
      expect(requests[1].headers['content-type']).toBeUndefined()
    })

    it('does not carry credentials to another origin', async () => {
      const { transport, requests } = fakePlugin(
        reply({ status: 307, headers: { location: 'https://other.example/dav/' } }),
        reply({ status: 207 })
      )
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      await nativeFetch(DAV, { method: 'PROPFIND', headers: { Authorization: 'Basic abc' }, body: '<p/>' })

      expect(requests[1].method).toBe('PROPFIND')
      expect(requests[1].headers.authorization).toBeUndefined()
    })

    it("returns the redirect itself under redirect: 'manual'", async () => {
      const { transport, requests } = fakePlugin(reply({ status: 302, headers: { Location: '/x' } }))
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      const res = await nativeFetch(DAV, { method: 'PUT', body: 'x', redirect: 'manual' })

      expect(res.status).toBe(302)
      expect(requests).toHaveLength(1)
    })

    it('gives up on a redirect loop', async () => {
      const loop = Array.from({ length: 25 }, () => reply({ status: 307, headers: { Location: DAV } }))
      const { transport } = fakePlugin(...loop)
      const nativeFetch = createNativeFetch(transport, fetch, APP_ORIGIN)

      await expect(nativeFetch(DAV, { method: 'PROPFIND', body: '<p/>' })).rejects.toThrow(/Too many redirects/)
    })
  })
})
