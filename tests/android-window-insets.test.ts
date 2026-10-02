import { describe, it, expect, afterEach } from 'vitest'
import { installWindowInsets, type HostInsets } from '../src/android/platform/window-insets'

/**
 * On a real phone Android dispatched the insets before the bundle had
 * installed `window.__xrncalInsets`, so the push was dropped and the header sat
 * under the status bar until the phone was rotated. installWindowInsets() now
 * pulls the latest insets once through the bridge as well.
 */

const g = globalThis as unknown as { window?: unknown; document?: unknown }

interface FakeWindow {
  XrncalNative: { windowInsets: () => string }
  __xrncalInsets?: (insets: HostInsets) => void
}

function installDom(windowInsetsReply: () => string): { vars: Map<string, string>; win: FakeWindow } {
  const vars = new Map<string, string>()
  const win: FakeWindow = { XrncalNative: { windowInsets: windowInsetsReply } }
  g.window = win
  g.document = { documentElement: { style: { setProperty: (name: string, value: string) => vars.set(name, value) } } }
  return { vars, win }
}

const envelope = (value: HostInsets | null): string => JSON.stringify({ ok: true, value })

describe('installWindowInsets', () => {
  const previous = { window: g.window, document: g.document }
  afterEach(() => {
    g.window = previous.window
    g.document = previous.document
  })

  it('pulls the insets the host recorded before the hook existed', () => {
    const { vars } = installDom(() => envelope({ top: 41.6, bottom: 16, left: 0, right: 41.6 }))

    installWindowInsets()

    expect(vars.get('--xrncal-inset-top')).toBe('41.6px')
    expect(vars.get('--xrncal-inset-bottom')).toBe('16px')
    expect(vars.get('--xrncal-inset-right')).toBe('41.6px')
  })

  it('keeps zeroes until a push when the host has seen no insets yet', () => {
    const { vars, win } = installDom(() => envelope(null))

    installWindowInsets()
    expect(vars.get('--xrncal-inset-top')).toBe('0px')

    win.__xrncalInsets!({ top: 24, bottom: 48, left: 0, right: 0 })
    expect(vars.get('--xrncal-inset-top')).toBe('24px')
    expect(vars.get('--xrncal-inset-bottom')).toBe('48px')
  })

  it('still installs the hook when the pull fails', () => {
    const { vars, win } = installDom(() => JSON.stringify({ ok: false, error: 'IllegalStateException: boom' }))
    const originalError = console.error
    console.error = () => {}
    try {
      installWindowInsets()
    } finally {
      console.error = originalError
    }

    win.__xrncalInsets!({ top: 30, bottom: 0, left: 0, right: 0 })
    expect(vars.get('--xrncal-inset-top')).toBe('30px')
  })
})
