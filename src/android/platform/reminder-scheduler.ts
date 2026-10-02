import { LocalNotifications } from '@capacitor/local-notifications'
import { App as CapacitorApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import { DateTime } from 'luxon'
import type { ISqliteDatabase } from './sqlite-driver'
import { EventsRepo } from '@main/db/repos/events-repo'
import { CalendarsRepo } from '@main/db/repos/calendars-repo'
import { mt } from '@main/i18n-main'
import i18n from '@renderer/i18n'
import { planReminderQueue, reminderSignature, type WantedReminder } from './reminder-queue'

/**
 * Android replacement for `src/main/notifications/reminder-scheduler.ts`.
 *
 * The desktop scheduler polls every minute and posts a notification the moment
 * an event enters a -1..+10 minute window. That works because a tray app is
 * always running. On Android it would be worse than useless: the WebView is
 * frozen when the app is backgrounded and torn down when the OS reclaims
 * memory, so the interval that is supposed to fire the reminder is exactly the
 * thing that stops running.
 *
 * So the *mechanism* inverts - reminders are handed to the OS ahead of time via
 * LocalNotifications, which delivers them whether or not the app is alive -
 * while the *behaviour* is unchanged: a reminder still arrives
 * NOTIFY_LEAD_MINUTES before an event starts, all-day events are still skipped,
 * and only visible calendars are considered.
 */

/** Reminders are queued this far ahead. Beyond a day the schedule churns more
 *  than it helps, since edits and sync keep rewriting it anyway. */
const SCHEDULE_HORIZON_HOURS = 36
/** Fire this many minutes before an event starts - same as desktop. */
const NOTIFY_LEAD_MINUTES = 10
/** Android refuses more than 500 pending alarms per app; stay well clear. */
const MAX_PENDING = 64

/**
 * Notification ids must be 32-bit ints, but occurrence ids are strings
 * (`<eventId>::<occurrenceStart>` for recurring instances). A stable hash keeps
 * re-scheduling idempotent: the same occurrence always maps to the same slot,
 * so a rewrite replaces its alarm instead of adding a duplicate.
 */
function occurrenceNotificationId(occurrenceId: string): number {
  let hash = 2166136261
  for (let i = 0; i < occurrenceId.length; i++) {
    hash ^= occurrenceId.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  // Keep it positive and inside the signed 32-bit range Android accepts.
  return Math.abs(hash | 0) || 1
}

export class ReminderScheduler {
  private eventsRepo: EventsRepo
  private calendarsRepo: CalendarsRepo
  private timer: ReturnType<typeof setInterval> | null = null
  private permissionGranted = false
  /** Guards against two overlapping rebuilds interleaving their cancels and
   *  schedules, which would leave stale alarms behind. */
  private rebuilding = false
  private localeListener: (() => void) | null = null
  private pauseListener: Promise<PluginListenerHandle> | null = null

  constructor(db: ISqliteDatabase) {
    this.eventsRepo = new EventsRepo(db)
    this.calendarsRepo = new CalendarsRepo(db)
  }

  /**
   * `intervalMs` is kept in the signature for parity with the desktop class,
   * but it means something different here: it is how often the *queue is
   * rebuilt* while the app happens to be in the foreground, not how often
   * reminders are checked. Delivery is the OS's job.
   *
   * Nothing tells the scheduler an event was created, edited or synced, so the
   * rebuild has to come round often: at 15 minutes an event made to start in
   * 12 got no alarm in time. A rebuild that finds nothing changed touches no
   * alarm (planReminderQueue), so a minute costs one query, as on desktop.
   */
  start(intervalMs = 60 * 1000): void {
    if (this.timer) clearInterval(this.timer)

    void this.ensurePermission().then(() => this.checkReminders())

    this.timer = setInterval(() => {
      this.checkReminders()
    }, intervalMs)

    // The interval stops when the app is backgrounded, which is exactly when
    // the queue matters - an event added just before leaving would otherwise
    // wait for the next time the app is opened. Rebuild on the way out.
    if (!this.pauseListener) {
      this.pauseListener = CapacitorApp.addListener('pause', () => this.checkReminders())
    }

    // Reminder text is rendered when the alarm is *queued*, not when it fires -
    // the app is usually not running at that point. Desktop formats at fire
    // time and so follows the language immediately; here an already-queued
    // reminder would keep the old language until the next rebuild. Requeue on
    // a language change so the two behave the same.
    if (!this.localeListener) {
      this.localeListener = () => this.checkReminders()
      i18n.on('languageChanged', this.localeListener)
    }
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
    if (this.pauseListener) {
      void this.pauseListener.then((h) => h.remove())
      this.pauseListener = null
    }
    if (this.localeListener) {
      i18n.off('languageChanged', this.localeListener)
      this.localeListener = null
    }
  }

  /** Rebuild the pending reminder queue from the current database state. */
  checkReminders(): void {
    void this.rebuildSchedule()
  }

  private async ensurePermission(): Promise<boolean> {
    if (this.permissionGranted) return true
    try {
      const current = await LocalNotifications.checkPermissions()
      if (current.display === 'granted') {
        this.permissionGranted = true
        return true
      }
      const requested = await LocalNotifications.requestPermissions()
      this.permissionGranted = requested.display === 'granted'
      return this.permissionGranted
    } catch (err) {
      console.warn('Notification permission unavailable:', err)
      return false
    }
  }

  private async rebuildSchedule(): Promise<void> {
    if (this.rebuilding) return
    this.rebuilding = true
    try {
      if (!(await this.ensurePermission())) return

      const nowUtc = DateTime.utc()
      const horizonUtc = nowUtc.plus({ hours: SCHEDULE_HORIZON_HOURS })

      const calendars = this.calendarsRepo.listCalendars()
      const activeCalIds = calendars.filter((c) => c.isVisible).map((c) => c.id)

      const wanted: WantedReminder[] = []

      if (activeCalIds.length > 0) {
        const occurrences = this.eventsRepo.queryEventsByRange(
          activeCalIds,
          nowUtc.toISO()!,
          horizonUtc.toISO()!
        )

        const candidates = occurrences
          .filter((occ) => !occ.allDay)
          .map((occ) => ({
            occ,
            fireAt: DateTime.fromISO(occ.startUtc, { zone: 'utc' }).minus({
              minutes: NOTIFY_LEAD_MINUTES
            })
          }))
          // An alarm in the past is dropped rather than delivered immediately;
          // the desktop grace window exists to catch a poll that ran late, and
          // there is no poll to run late here.
          .filter((c) => c.fireAt > nowUtc)
          .sort((a, b) => a.fireAt.toMillis() - b.fireAt.toMillis())
          .slice(0, MAX_PENDING)

        for (const { occ, fireAt } of candidates) {
          const localStart = DateTime.fromISO(occ.startUtc, { zone: 'utc' }).setZone('local')
          const timeStr = localStart.toFormat('HH:mm')
          wanted.push({
            id: occurrenceNotificationId(occ.id),
            title: mt('notify.reminder', { title: occ.title }),
            body: `${timeStr}${occ.location ? ` @ ${occ.location}` : ''} — ${mt('notify.startsSoon')}`,
            at: fireAt.toJSDate(),
            startUtc: occ.startUtc
          })
        }
      }

      // Reconcile against what is already queued rather than cancelling
      // everything and re-adding: a blanket cancel/re-add races with delivery,
      // and an alarm due in the next few seconds can be lost in the gap. Only
      // alarms that are gone or whose text or time changed are touched.
      const pending = await LocalNotifications.getPending()
      const plan = planReminderQueue(wanted, pending.notifications)

      if (plan.cancel.length > 0) {
        await LocalNotifications.cancel({ notifications: plan.cancel.map((id) => ({ id })) })
      }

      const toSchedule = plan.schedule.map((n) => ({
        id: n.id,
        title: n.title,
        body: n.body,
        schedule: { at: n.at, allowWhileIdle: true },
        smallIcon: 'ic_stat_xrncal',
        // `sig` is what the next rebuild compares; `startUtc` is what a tap
        // opens (use-reminder-taps.ts).
        extra: { notificationId: n.id, startUtc: n.startUtc, sig: reminderSignature(n) }
      }))

      if (toSchedule.length > 0) {
        await LocalNotifications.schedule({ notifications: toSchedule })
      }
    } catch (err) {
      console.error('Failed to rebuild reminder schedule:', err)
    } finally {
      this.rebuilding = false
    }
  }
}
