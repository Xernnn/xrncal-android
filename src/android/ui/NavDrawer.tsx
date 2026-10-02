import React from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import {
  List,
  Rows3,
  Columns3,
  RectangleVertical,
  Grid3x3,
  Search,
  RefreshCw,
  CircleUser,
  Settings
} from 'lucide-react'
import type { AppShellContext } from '@renderer/App'
import type { CalendarViewType } from '@shared/visible-range'

interface Props {
  open: boolean
  onClose: () => void
  shell: AppShellContext
}

// Keys are the existing `views.*` entries the desktop ViewSwitcher uses.
const VIEWS: { id: CalendarViewType; icon: React.ElementType; labelKey: string }[] = [
  { id: 'list', icon: List, labelKey: 'views.list' },
  { id: 'month', icon: Rows3, labelKey: 'views.month' },
  { id: 'week', icon: Columns3, labelKey: 'views.week' },
  { id: 'day', icon: RectangleVertical, labelKey: 'views.day' },
  { id: 'year', icon: Grid3x3, labelKey: 'views.year' }
]

/**
 * The navigation drawer: the five views, then search, sync, accounts and
 * settings - the app's whole menu, which on desktop is spread across the
 * header's view switcher and overflow menu.
 *
 * Calendar visibility lives in the header's filter menu and date picking on
 * the header title, so this is a plain list.
 *
 * Rendered through a portal to document.body. App puts the sidebar slot inside
 * its content row, which carries `z-10` and is a flex item - so it establishes
 * a stacking context, and *everything* inside it paints below the header no
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

      <nav
        className={`gc-drawer fixed inset-y-0 left-0 z-50 flex w-[78vw] max-w-[320px] flex-col overflow-y-auto bg-sidebar py-2 transition-transform duration-200 ease-[var(--ease-out)] ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
        aria-hidden={!open}
      >
        {VIEWS.map((view) => (
          <DrawerItem
            key={view.id}
            icon={view.icon}
            label={t(view.labelKey)}
            active={shell.currentView === view.id}
            onClick={run(() => shell.setCurrentView(view.id))}
          />
        ))}

        <Divider />
        <DrawerItem icon={Search} label={t('actions.search')} onClick={run(shell.openSearch)} />

        <Divider />
        <DrawerItem icon={RefreshCw} label={t('mobile.sync')} onClick={run(() => void shell.syncNow())} />

        <Divider />
        <DrawerItem icon={CircleUser} label={t('mobile.accounts')} onClick={run(shell.openAccounts)} />
        <DrawerItem icon={Settings} label={t('settings.title')} onClick={run(shell.openSettings)} />
      </nav>
    </>,
    document.body
  )
}

const Divider: React.FC = () => <div className="my-2 border-t border-hairline" />

const DrawerItem: React.FC<{
  icon: React.ElementType
  label: string
  active?: boolean
  onClick: () => void
}> = ({ icon: Icon, label, active = false, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    aria-current={active ? 'page' : undefined}
    className={`mx-2 flex min-h-[52px] items-center gap-6 rounded-[4px] px-4 text-[16px] transition-colors ${
      active ? 'gc-drawer-active' : 'text-primary active:bg-hover'
    }`}
  >
    <Icon size={24} className={active ? '' : 'text-muted'} />
    <span className="truncate">{label}</span>
  </button>
)

export default NavDrawer
