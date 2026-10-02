import { useEffect, type RefObject } from 'react'
import type { AppShellContext } from '@renderer/App'
import { TOUCH_DRAG_START_EVENT } from './touch-drag'

/** Minimum horizontal travel before a touch counts as a period swipe. */
const SWIPE_THRESHOLD_PX = 60
/** A gesture must be this much more horizontal than vertical to count. */
const DIRECTION_RATIO = 1.6
/** Slow drags are usually a mis-grab, not a flick. */
const MAX_DURATION_MS = 600

/**
 * Swipe left/right to move a period, replacing the desktop header arrows.
 *
 * Active in every view. Week view used to be left out because it scrolled
 * sideways through three columns on a phone; it now shows all seven, so the
 * swipe was its only way to the next week and leaving it out stranded it.
 *
 * A swipe may start on an event block. A quick flick never becomes a drag -
 * touch-drag cancels its long press as soon as the finger moves - and when a
 * held press does arm one, touch-drag says so and the gesture is dropped
 * here, so a drag never also flips the period. Sheets, dialogs and the
 * header's menus are excluded: a swipe there must not change the calendar
 * behind them.
 */
export function useSwipeNavigation(shellRef: RefObject<AppShellContext | null>): void {
  useEffect(() => {
    let startX = 0
    let startY = 0
    let startedAt = 0
    let tracking = false

    const onTouchStart = (e: TouchEvent): void => {
      const shell = shellRef.current
      if (!shell) return
      if (e.touches.length !== 1) return

      const target = e.target as HTMLElement | null
      if (
        target?.closest(
          '.gc-drawer, .gc-header-popover, .gc-fullscreen-sheet, .gc-dialog, .gc-overlay, [role="dialog"]'
        )
      ) {
        return
      }

      startX = e.touches[0].clientX
      startY = e.touches[0].clientY
      startedAt = Date.now()
      tracking = true
    }

    const onTouchEnd = (e: TouchEvent): void => {
      if (!tracking) return
      tracking = false

      const shell = shellRef.current
      if (!shell) return

      const touch = e.changedTouches[0]
      if (!touch) return

      const dx = touch.clientX - startX
      const dy = touch.clientY - startY

      if (Date.now() - startedAt > MAX_DURATION_MS) return
      if (Math.abs(dx) < SWIPE_THRESHOLD_PX) return
      if (Math.abs(dx) < Math.abs(dy) * DIRECTION_RATIO) return

      // Swiping left pulls the next period into view, matching the direction
      // of travel users expect from a paged surface.
      if (dx < 0) shell.goNext()
      else shell.goPrev()
    }

    const onTouchCancel = (): void => {
      tracking = false
    }

    document.addEventListener('touchstart', onTouchStart, { passive: true })
    document.addEventListener(TOUCH_DRAG_START_EVENT, onTouchCancel)
    document.addEventListener('touchend', onTouchEnd, { passive: true })
    document.addEventListener('touchcancel', onTouchCancel, { passive: true })

    return () => {
      document.removeEventListener('touchstart', onTouchStart)
      document.removeEventListener('touchend', onTouchEnd)
      document.removeEventListener('touchcancel', onTouchCancel)
      document.removeEventListener(TOUCH_DRAG_START_EVENT, onTouchCancel)
    }
  }, [shellRef])
}
