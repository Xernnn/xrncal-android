import React from 'react'
import { useTranslation } from 'react-i18next'
import { Menu, Plus, Search, Calendar, AlertTriangle } from 'lucide-react'
import type { PeriodTitle } from '@shared/compact-labels'

interface Props {
  period: PeriodTitle
  /** Day of the month printed inside the go-to-today icon. */
  todayDay: number
  conflictCount: number
  onOpenDrawer: () => void
  onPickDate: () => void
  onCreate: () => void
  onSearch: () => void
  onToday: () => void
  onOpenConflicts: () => void
}

/**
 * The phone top bar, in the desktop header's language: quiet type, muted
 * `gc-icon-btn`-style buttons, a hairline under it. Menu, the period (which
 * opens a month picker), then create, search and go-to-today. Views and
 * calendars are in the drawer; periods change by horizontal swipe.
 *
 * The bar sits below the status bar - StatusBar.setOverlaysWebView(true) means
 * the WebView paints underneath it, so the inset is added as padding in
 * mobile.css.
 */
const MobileHeader: React.FC<Props> = ({
  period,
  todayDay,
  conflictCount,
  onOpenDrawer,
  onPickDate,
  onCreate,
  onSearch,
  onToday,
  onOpenConflicts
}) => {
  const { t } = useTranslation()

  return (
    <header className="gc-mobile-header flex items-center gap-0.5 border-b border-hairline bg-surface px-1.5">
      <IconButton label={t('mobile.menu')} onClick={onOpenDrawer}>
        <Menu size={20} />
      </IconButton>

      <button
        type="button"
        onClick={onPickDate}
        aria-label={t('mobile.pickDate')}
        className="flex min-w-0 flex-1 flex-col items-start justify-center rounded-[3px] px-2 py-1 text-left active:bg-hover"
      >
        <span className="w-full truncate text-[19px] leading-tight font-normal text-primary">{period.title}</span>
        {period.subtitle && (
          <span className="w-full truncate text-[12px] leading-tight text-muted">{period.subtitle}</span>
        )}
      </button>

      {conflictCount > 0 && (
        <IconButton label={t('mobile.conflicts')} onClick={onOpenConflicts}>
          <span className="relative">
            <AlertTriangle size={19} className="text-today" />
            <span className="absolute -top-1 -right-1.5 min-w-[14px] rounded-[3px] bg-today px-0.5 text-center text-[9px] leading-[14px] font-bold text-white">
              {conflictCount > 9 ? '9+' : conflictCount}
            </span>
          </span>
        </IconButton>
      )}

      <IconButton label={t('mobile.newEvent')} onClick={onCreate}>
        <Plus size={21} />
      </IconButton>

      <IconButton label={t('actions.search')} onClick={onSearch}>
        <Search size={19} />
      </IconButton>

      {/* A calendar glyph carrying today's date, so "go to today" reads as
          today rather than as a generic calendar button. */}
      <IconButton label={t('nav.today')} onClick={onToday}>
        <span className="relative flex items-center justify-center">
          <Calendar size={21} />
          <span className="absolute top-[8px] text-[9px] leading-none font-semibold tabular-nums">{todayDay}</span>
        </span>
      </IconButton>
    </header>
  )
}

const IconButton: React.FC<{
  label: string
  onClick: () => void
  children: React.ReactNode
}> = ({ label, onClick, children }) => (
  <button
    type="button"
    aria-label={label}
    onClick={onClick}
    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[3px] text-muted transition-colors active:bg-hover active:text-primary"
  >
    {children}
  </button>
)

export default MobileHeader
