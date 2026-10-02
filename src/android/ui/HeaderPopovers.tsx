import React from 'react'
import { createPortal } from 'react-dom'
import MiniCalendar from '@renderer/components/MiniCalendar'
import type { AppShellContext } from '@renderer/App'

/**
 * The month picker the header's period title opens, styled as a desktop
 * `gc-menu` (hairline border, 4px radius, surface). It drops from under the
 * header, closes on a tap outside or on Back, and is portalled to <body> for
 * the same stacking reason as the drawer.
 */
export const DatePickerMenu: React.FC<{
  shell: AppShellContext
  onClose: () => void
}> = ({ shell, onClose }) =>
  createPortal(
    <>
      {/* Invisible: the calendar stays readable behind the menu, and a tap
          anywhere outside it only closes it. */}
      <div className="fixed inset-0 z-40" onClick={onClose} aria-hidden="true" />
      <div className="gc-header-popover fixed left-2 z-50 w-[min(92vw,340px)] overflow-y-auto border border-hairline bg-surface p-3 shadow-lg">
        <MiniCalendar
          anchorDate={shell.anchorDate}
          occurrences={shell.occurrences}
          firstDayOfWeek={shell.firstDayOfWeek}
          onSelectDate={(date) => {
            shell.setAnchorDate(date)
            onClose()
          }}
          onPrevMonth={() => shell.setAnchorDate((d) => d.minus({ months: 1 }))}
          onNextMonth={() => shell.setAnchorDate((d) => d.plus({ months: 1 }))}
        />
      </div>
    </>,
    document.body
  )
