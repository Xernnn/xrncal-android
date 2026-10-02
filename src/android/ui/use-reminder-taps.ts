import { useEffect, type MutableRefObject } from 'react'
import { DateTime } from 'luxon'
import { LocalNotifications } from '@capacitor/local-notifications'
import type { AppShellContext } from '@renderer/App'

/**
 * Tapping a reminder opens the calendar on the day of the event it is about.
 *
 * The scheduler puts the occurrence's start in each alarm's `extra`
 * (reminder-scheduler.ts). Capacitor retains this event until a listener
 * consumes it, so a tap that cold-starts the app is still delivered once the
 * shell has mounted.
 */
export function useReminderTaps(shellRef: MutableRefObject<AppShellContext | null>): void {
  useEffect(() => {
    const handle = LocalNotifications.addListener('localNotificationActionPerformed', ({ notification }) => {
      const startUtc = (notification.extra as { startUtc?: unknown } | undefined)?.startUtc
      if (typeof startUtc !== 'string') return
      const day = DateTime.fromISO(startUtc, { zone: 'utc' }).toLocal()
      if (day.isValid) shellRef.current?.setAnchorDate(day)
    })
    return () => {
      void handle.then((h) => h.remove())
    }
  }, [shellRef])
}
