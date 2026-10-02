import React from 'react'
import { createPortal } from 'react-dom'
import { Check } from 'lucide-react'
import MiniCalendar from '@renderer/components/MiniCalendar'
import type { AppShellContext } from '@renderer/App'
import type { Calendar } from '@shared/event-model'

/**
 * The two menus the phone header opens: which calendars are shown, and a
 * month picker to jump to a date. Both drop from under the header, close on a
 * tap outside or on Back, and are portalled to <body> for the same stacking
 * reason as the drawer.
 */

const Popover: React.FC<{
  side: 'left' | 'right'
  onClose: () => void
  className?: string
  children: React.ReactNode
}> = ({ side, onClose, className = '', children }) =>
  createPortal(
    <>
      {/* Invisible: the calendar stays readable behind a menu, and a tap
          anywhere outside it only closes it. */}
      <div className="fixed inset-0 z-40" onClick={onClose} aria-hidden="true" />
      <div
        className={`gc-header-popover fixed z-50 overflow-y-auto bg-dialog shadow-2xl ${
          side === 'right' ? 'right-2' : 'left-2'
        } ${className}`}
      >
        {children}
      </div>
    </>,
    document.body
  )

/** Calendars with a checkbox drawn in each calendar's own colour. */
export const CalendarFilterMenu: React.FC<{
  calendars: Calendar[]
  onToggle: (calendar: Calendar) => void
  onClose: () => void
}> = ({ calendars, onToggle, onClose }) => (
  <Popover side="right" onClose={onClose} className="w-[72vw] max-w-[320px]">
    <ul>
      {calendars.map((cal) => (
        <li key={cal.id} className="border-b border-hairline last:border-b-0">
          <button
            type="button"
            role="menuitemcheckbox"
            aria-checked={cal.isVisible}
            onClick={() => onToggle(cal)}
            className="flex min-h-[56px] w-full items-center gap-5 px-4 text-left active:bg-hover"
          >
            <span
              className="flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-[2px] border-2"
              style={{
                borderColor: cal.color,
                backgroundColor: cal.isVisible ? cal.color : 'transparent'
              }}
            >
              {cal.isVisible && <Check size={16} strokeWidth={3} className="text-white" />}
            </span>
            <span className="min-w-0 flex-1 truncate text-[16px] text-primary">{cal.name}</span>
          </button>
        </li>
      ))}
    </ul>
  </Popover>
)

/** The month grid the desktop sidebar shows, as a drop-down from the title. */
export const DatePickerMenu: React.FC<{
  shell: AppShellContext
  onClose: () => void
}> = ({ shell, onClose }) => (
  <Popover side="left" onClose={onClose} className="w-[min(92vw,340px)] p-3">
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
  </Popover>
)
