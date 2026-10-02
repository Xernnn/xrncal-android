import React from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { Calendar, Columns3, CalendarDays, CalendarRange, LayoutList, RefreshCw, Users, Settings } from 'lucide-react'
import MiniCalendar from '@renderer/components/MiniCalendar'
import { Checkbox } from '@renderer/components/ui'
import type { AppShellContext } from '@renderer/App'
import type { CalendarViewType } from '@shared/visible-range'

interface Props {
  open: boolean
  onClose: () => void
  shell: AppShellContext
}

// The desktop ViewSwitcher's icons and labels, so a view reads the same on
// both. Desktop picks a view from a menu in the header; a phone header has no
// room for it, so the menu's rows live here.
const VIEWS: { id: CalendarViewType; icon: React.ElementType; labelKey: string }[] = [
  { id: 'day', icon: Calendar, labelKey: 'views.day' },
  { id: 'week', icon: Columns3, labelKey: 'views.week' },
  { id: 'month', icon: CalendarDays, labelKey: 'views.month' },
  { id: 'year', icon: CalendarRange, labelKey: 'views.year' },
  { id: 'list', icon: LayoutList, labelKey: 'views.list' }
]

/**
 * The desktop sidebar as an off-canvas drawer, plus what a phone has nowhere
 * else to put: the view list (desktop's header menu) and calendar visibility
 * (desktop's settings). Same tokens as the sidebar - `bg-sidebar`, 13px rows,
 * `rounded-[3px]`, `bg-hover` for the current item - so it reads as xrncal,
 * not as a stock Android drawer.
 *
 * Rendered through a portal to document.body. App puts the sidebar slot inside
 * its content row, which carries `z-10` and is a flex item - so it establishes
 * a stacking context, and everything inside it paints below the header no
 * matter how high its own z-index goes. The portal lifts it out entirely.
 */
const NavDrawer: React.FC<Props> = ({ open, onClose, shell }) => {
  const { t } = useTranslation()

  const run = (action: () => void) => () => {
    onClose()
    action()
  }

  return createPortal(
    <>
      <div
        className={`fixed inset-0 z-40 bg-black/50 transition-opacity duration-200 ${
          open ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
        onClick={onClose}
        aria-hidden="true"
      />

      <aside
        className={`gc-drawer fixed inset-y-0 left-0 z-50 flex w-[84vw] max-w-[320px] flex-col overflow-y-auto border-r border-hairline bg-sidebar transition-transform duration-200 ease-[var(--ease-out)] ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
        aria-hidden={!open}
      >
        <div className="px-2 pt-3 pb-2">
          {VIEWS.map((view) => (
            <DrawerRow
              key={view.id}
              icon={view.icon}
              label={t(view.labelKey)}
              active={shell.currentView === view.id}
              onClick={run(() => shell.setCurrentView(view.id))}
            />
          ))}
        </div>

        <div className="border-t border-hairline px-3.5 py-3">
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

        <div className="border-t border-hairline px-2 py-3">
          <h2 className="mb-1 px-2.5 text-[11px] font-semibold tracking-wide text-muted uppercase">
            {t('mobile.calendars')}
          </h2>
          <ul>
            {shell.calendars.map((cal) => (
              <li key={cal.id}>
                <label className="flex min-h-[44px] cursor-pointer items-center gap-2.5 rounded-[3px] px-2.5 active:bg-hover">
                  <Checkbox checked={cal.isVisible} onChange={() => void shell.toggleCalendarVisibility(cal)} />
                  <span className="h-2.5 w-2.5 shrink-0 rounded-[2px]" style={{ backgroundColor: cal.color }} />
                  <span className="min-w-0 flex-1 truncate text-[14px] text-primary">{cal.name}</span>
                </label>
              </li>
            ))}
          </ul>
        </div>

        <div className="mt-auto border-t border-hairline px-2 py-2">
          <DrawerRow icon={RefreshCw} label={t('mobile.sync')} onClick={run(() => void shell.syncNow())} />
          <DrawerRow icon={Users} label={t('mobile.accounts')} onClick={run(shell.openAccounts)} />
          <DrawerRow icon={Settings} label={t('settings.title')} onClick={run(shell.openSettings)} />
        </div>
      </aside>
    </>,
    document.body
  )
}

/** A `gc-menu-item` row at touch height. */
const DrawerRow: React.FC<{
  icon: React.ElementType
  label: string
  active?: boolean
  onClick: () => void
}> = ({ icon: Icon, label, active = false, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    aria-current={active ? 'page' : undefined}
    className={`flex min-h-[44px] w-full items-center gap-3 rounded-[3px] px-2.5 text-left text-[14px] text-primary transition-colors active:bg-hover ${
      active ? 'bg-hover font-medium' : ''
    }`}
  >
    <Icon size={17} className={active ? 'text-primary' : 'text-muted'} />
    <span className="truncate">{label}</span>
  </button>
)

export default NavDrawer
