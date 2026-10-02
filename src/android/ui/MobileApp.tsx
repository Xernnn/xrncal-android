import React, { useCallback, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { DateTime } from 'luxon'
import { App as CapacitorApp } from '@capacitor/app'
import App, { type AppShellContext } from '@renderer/App'
import { headerPeriod } from '@shared/compact-labels'
import MobileHeader from './MobileHeader'
import NavDrawer from './NavDrawer'
import { CalendarFilterMenu, DatePickerMenu } from './HeaderPopovers'
import { useSwipeNavigation } from './use-swipe-navigation'
import { useAndroidBackButton, dismissTopLayer } from './use-back-button'

/** The one piece of shell chrome that can be open over the calendar. */
type ShellLayer = 'drawer' | 'filter' | 'datePicker' | null

/**
 * The Android shell.
 *
 * `App` is the desktop container, imported unchanged - it still owns every
 * piece of calendar state, all five views, the editor, drag/drop and the
 * modals. This file supplies only the chrome through App's render props, and
 * turns on `compact`, which is what makes the shared views lay out for a
 * phone. There is exactly one implementation of the calendar itself and no
 * second copy to keep in step.
 *
 * The chrome is a top bar and a navigation drawer, with no bottom bar: views
 * are switched from the drawer, create / filter / today sit in the top bar,
 * and periods change by horizontal swipe.
 */
const MobileApp: React.FC = () => {
  const { t, i18n } = useTranslation()
  const [layer, setLayer] = useState<ShellLayer>(null)
  // The shell context is handed to us on every render of App; keeping the
  // latest in a ref lets the gesture handlers below reach it without
  // re-subscribing their listeners on each frame.
  const shellRef = useRef<AppShellContext | null>(null)

  const closeLayer = useCallback(() => setLayer(null), [])
  const toggleLayer = (next: Exclude<ShellLayer, null>) => setLayer((open) => (open === next ? null : next))

  useSwipeNavigation(shellRef)
  useAndroidBackButton(() => {
    const dismissed = dismissTopLayer({
      isOverlayOpen: Boolean(shellRef.current?.isOverlayOpen),
      shellLayerOpen: layer !== null,
      closeShellLayer: closeLayer
    })
    // Nothing left to close. Minimise rather than exit, so returning to the
    // app keeps the warm database handle and the current view.
    if (!dismissed) void CapacitorApp.minimizeApp()
  })

  return (
    <App
      compact
      renderHeader={(shell) => {
        shellRef.current = shell
        return (
          <MobileHeader
            period={headerPeriod(shell.currentView, shell.anchorDate, i18n.language, (n) =>
              t('mobile.weekNumber', { n })
            )}
            todayDay={DateTime.local().day}
            conflictCount={shell.conflictCount}
            filterOpen={layer === 'filter'}
            onOpenDrawer={() => setLayer('drawer')}
            onPickDate={() => toggleLayer('datePicker')}
            onCreate={() => {
              closeLayer()
              shell.newEventOn(shell.anchorDate)
            }}
            onToggleFilter={() => toggleLayer('filter')}
            onToday={() => {
              closeLayer()
              shell.goToday()
            }}
            onOpenConflicts={shell.openConflicts}
          />
        )
      }}
      renderSidebar={(shell) => (
        <>
          <NavDrawer open={layer === 'drawer'} onClose={closeLayer} shell={shell} />
          {layer === 'filter' && (
            <CalendarFilterMenu
              calendars={shell.calendars}
              onToggle={(cal) => void shell.toggleCalendarVisibility(cal)}
              onClose={closeLayer}
            />
          )}
          {layer === 'datePicker' && <DatePickerMenu shell={shell} onClose={closeLayer} />}
        </>
      )}
    />
  )
}

export default MobileApp
