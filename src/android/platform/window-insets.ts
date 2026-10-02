/**
 * Publishes the host's real window insets as CSS variables.
 *
 * `env(safe-area-inset-*)` is not enough on Android. In a WebView those values
 * only ever describe *display cutouts* - the notch - and stay 0 for the status
 * bar and the gesture/navigation bar. Since the app draws edge to edge
 * (targetSdk 35 makes that the default, and StatusBar.setOverlaysWebView makes
 * it explicit), relying on env() puts the bottom navigation underneath the
 * gesture pill and the header under the clock.
 *
 * MainActivity therefore reads the true insets from the window and calls
 * `window.__xrncalInsets`, which is installed here. The values are CSS pixels,
 * already divided by the display density on the native side.
 *
 * The push alone is not enough. Android dispatches insets on the first layout,
 * usually while the bundle is still loading, so that call finds no hook and is
 * dropped - and nothing re-sends until the insets change. On a phone held still
 * that meant zeroes for the whole session: the header under the clock. So the
 * hook is installed first and then the latest insets are pulled once through
 * the synchronous bridge. Either order of push and pull ends with the right
 * values, because MainActivity records the payload before it pushes it.
 */

import { hasNativeBridge, nativeBridge, unwrap } from '../native/bridge'

export interface HostInsets {
  top: number
  bottom: number
  left: number
  right: number
}

declare global {
  interface Window {
    __xrncalInsets?: (insets: HostInsets) => void
  }
}

function apply(insets: HostInsets): void {
  const style = document.documentElement.style
  style.setProperty('--xrncal-inset-top', `${insets.top}px`)
  style.setProperty('--xrncal-inset-bottom', `${insets.bottom}px`)
  style.setProperty('--xrncal-inset-left', `${insets.left}px`)
  style.setProperty('--xrncal-inset-right', `${insets.right}px`)
}

export function installWindowInsets(): void {
  // Sensible zeroes so the layout is correct even if the host never calls -
  // the CSS falls back to env() behind these.
  apply({ top: 0, bottom: 0, left: 0, right: 0 })

  const receive = (insets: HostInsets): void => {
    // Guard against a malformed payload rather than writing NaN into the
    // custom properties, which would silently collapse the padding.
    const safe = (n: unknown): number => (typeof n === 'number' && isFinite(n) && n >= 0 ? n : 0)
    apply({
      top: safe(insets?.top),
      bottom: safe(insets?.bottom),
      left: safe(insets?.left),
      right: safe(insets?.right)
    })
  }
  window.__xrncalInsets = receive

  if (!hasNativeBridge()) return
  try {
    const latest = unwrap<HostInsets | null>('windowInsets', nativeBridge().windowInsets())
    if (latest) receive(latest)
  } catch (err) {
    // The zeroes stand until the next push, a rotation say, corrects them.
    console.error('Could not read the window insets:', err)
  }
}
