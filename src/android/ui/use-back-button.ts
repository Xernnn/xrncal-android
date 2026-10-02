import { useEffect, useRef } from 'react'
import { App as CapacitorApp } from '@capacitor/app'

/**
 * Runs `onBack` when the Android Back gesture fires.
 *
 * The handler is kept in a ref and the listener registered once. That is not
 * just an optimisation: the shell learns about open dialogs through a ref that
 * `App` refreshes as it renders, and `App` re-rendering does not re-render the
 * shell around it. A handler captured in the effect's closure would therefore
 * still believe no dialog was open. Reading through the ref means Back always
 * sees the state as it is at the moment it is pressed.
 */
export function useAndroidBackButton(onBack: () => void): void {
  const latest = useRef(onBack)
  latest.current = onBack

  useEffect(() => {
    const handle = CapacitorApp.addListener('backButton', () => latest.current())
    return () => {
      void handle.then((h) => h.remove())
    }
  }, [])
}

/**
 * Dismiss one layer, topmost first, and report whether anything was dismissed.
 *
 * Dialogs are closed by dispatching `Escape` rather than by hunting the DOM
 * for a close button. `App.tsx` already owns a complete Escape hierarchy - the
 * recurring-scope prompt, then shortcuts, conflicts, accounts, settings, then
 * clearing the selection - and the editor and search palette handle their own.
 * Reusing it means Back and Escape can never disagree, and any dialog added
 * later gets Back support for free.
 */
/**
 * Layers that Back should dismiss, detected in the DOM.
 *
 * `App` also reports `isOverlayOpen` through the shell context, but that value
 * reaches this file through a ref that `App` refreshes as it renders, and the
 * shell around it does not re-render when `App`'s own state changes - so it
 * can lag. The rendered DOM cannot: if a dialog is on screen, its node is
 * there. Both are consulted and either one is enough.
 *
 * `.gc-overlay` is deliberately absent: it is the backdrop that wraps
 * `.gc-dialog`, so matching the dialog alone avoids counting the same layer
 * twice. The drawer is always present in the DOM (parked off-screen with a
 * transform), which is why it - and the header's menus with it - is tracked by
 * the shell's own state rather than by selector.
 */
const OVERLAY_SELECTOR =
  // EventEditorDialog renders either a centred modal (.gc-dialog) or a side
  // panel, and the panel picks its side at open time - hence both slide
  // classes. Missing one of them made Back leave the app while the editor was
  // still on screen. In the phone layout the editor and settings are
  // full-screen sheets instead.
  '.gc-dialog, .gc-slide-right, .gc-slide-left, .gc-fullscreen-sheet, .gc-dnd-overlay'

export function dismissTopLayer(state: {
  isOverlayOpen: boolean
  /** The drawer or one of the header's menus. */
  shellLayerOpen: boolean
  closeShellLayer: () => void
}): boolean {
  const overlayOnScreen = Boolean(document.querySelector(OVERLAY_SELECTOR))
  if (state.isOverlayOpen || overlayOnScreen) {
    // Dispatched where a real key press would land - the focused element -
    // so it bubbles through element-level handlers on its way to document and
    // window. Fired at document it skipped them: the search palette closes on
    // its input's onKeyDown, so Back could not close it, and App leaves
    // Escape to the palette while it is open, so every dialog opened after it
    // stacked behind it with Back dead.
    const target = document.activeElement instanceof HTMLElement ? document.activeElement : document
    target.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    )
    return true
  }
  if (state.shellLayerOpen) {
    state.closeShellLayer()
    return true
  }
  return false
}
