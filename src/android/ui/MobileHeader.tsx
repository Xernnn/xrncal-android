import React from 'react'
import { useTranslation } from 'react-i18next'
import { Menu, Plus, ListFilter, Calendar, AlertTriangle } from 'lucide-react'
import type { PeriodTitle } from '@shared/compact-labels'

interface Props {
  period: PeriodTitle
  /** Day of the month printed inside the go-to-today icon. */
  todayDay: number
  conflictCount: number
  filterOpen: boolean
  onOpenDrawer: () => void
  onPickDate: () => void
  onCreate: () => void
  onToggleFilter: () => void
  onToday: () => void
  onOpenConflicts: () => void
}

/**
 * The phone top bar: menu, a two-line period title ("Sep / Oct" over
 * "week 40") that opens a date picker, then create, calendar filter and
 * go-to-today. Views are switched from the drawer and periods by horizontal
 * swipe, so neither needs a control here.
 *
 * The bar sits below the status bar - StatusBar.setOverlaysWebView(true) means
 * the WebView paints underneath it, so the inset is added as padding in
 * mobile.css.
 */
const MobileHeader: React.FC<Props> = ({
  period,
  todayDay,
  conflictCount,
  filterOpen,
  onOpenDrawer,
  onPickDate,
  onCreate,
  onToggleFilter,
  onToday,
  onOpenConflicts
}) => {
  const { t } = useTranslation()

  return (
    <header className="gc-mobile-header flex items-center border-b border-hairline bg-sidebar px-1 text-primary">
      <IconButton label={t('mobile.menu')} onClick={onOpenDrawer}>
        <Menu size={24} />
      </IconButton>

      <button
        type="button"
        onClick={onPickDate}
        aria-label={t('mobile.pickDate')}
        className="flex min-w-0 flex-1 flex-col items-start justify-center px-3 py-1 text-left active:opacity-70"
      >
        <span className="w-full truncate text-[21px] leading-tight font-medium">{period.title}</span>
        {period.subtitle && (
          <span className="w-full truncate text-[14px] leading-tight text-muted">{period.subtitle}</span>
        )}
      </button>

      {conflictCount > 0 && (
        <IconButton label={t('mobile.conflicts')} onClick={onOpenConflicts}>
          <span className="relative">
            <AlertTriangle size={21} className="text-[var(--color-today-mark)]" />
            <span className="absolute -top-1 -right-1.5 min-w-[14px] rounded-[3px] bg-[var(--color-today-mark)] px-0.5 text-center text-[9px] leading-[14px] font-bold text-white">
              {conflictCount > 9 ? '9+' : conflictCount}
            </span>
          </span>
        </IconButton>
      )}

      <IconButton label={t('mobile.newEvent')} onClick={onCreate}>
        <Plus size={26} strokeWidth={2.2} />
      </IconButton>

      <IconButton label={t('mobile.filterCalendars')} onClick={onToggleFilter} expanded={filterOpen}>
        <ListFilter size={24} />
      </IconButton>

      {/* A calendar glyph carrying today's date, so "go to today" reads as
          today rather than as a generic calendar button. */}
      <IconButton label={t('nav.today')} onClick={onToday}>
        <span className="relative flex items-center justify-center">
          <Calendar size={26} strokeWidth={1.9} />
          <span className="absolute top-[9px] text-[11px] leading-none font-bold tabular-nums">{todayDay}</span>
        </span>
      </IconButton>
    </header>
  )
}

const IconButton: React.FC<{
  label: string
  onClick: () => void
  expanded?: boolean
  children: React.ReactNode
}> = ({ label, onClick, expanded, children }) => (
  <button
    type="button"
    aria-label={label}
    aria-expanded={expanded}
    onClick={onClick}
    className="flex h-12 w-12 shrink-0 items-center justify-center rounded-[3px] transition-colors active:bg-hover"
  >
    {children}
  </button>
)

export default MobileHeader
