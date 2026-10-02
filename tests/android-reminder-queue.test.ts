import { describe, it, expect } from 'vitest'
import {
  planReminderQueue,
  reminderSignature,
  type PendingReminder,
  type WantedReminder
} from '../src/android/platform/reminder-queue'

/**
 * Notification ids hash the occurrence id, so they survive edits that keep the
 * occurrence. Reconciling by id alone left those alarms queued with their old
 * text, or firing at their old time.
 */
function wanted(id: number, title: string, at = '2026-10-05T07:50:00.000Z'): WantedReminder {
  return { id, title, body: '18:00 — starts soon', at: new Date(at), startUtc: '2026-10-05T08:00:00.000Z' }
}

const queued = (r: WantedReminder): PendingReminder => ({ id: r.id, extra: { sig: reminderSignature(r) } })

describe('planReminderQueue', () => {
  it('touches nothing when the queue already matches', () => {
    const a = wanted(1, 'Standup')
    const b = wanted(2, 'Dinner')
    expect(planReminderQueue([a, b], [queued(a), queued(b)])).toEqual({ cancel: [], schedule: [] })
  })

  it('schedules new reminders and cancels ones no longer wanted', () => {
    const keep = wanted(1, 'Standup')
    const added = wanted(2, 'Dinner')
    const plan = planReminderQueue([keep, added], [queued(keep), { id: 9, extra: { sig: 'x' } }])
    expect(plan.cancel).toEqual([9])
    expect(plan.schedule.map((r) => r.id)).toEqual([2])
  })

  it('replaces a reminder whose title changed under the same id', () => {
    const before = wanted(1, 'Standup')
    const after = wanted(1, 'Standup (moved room)')
    const plan = planReminderQueue([after], [queued(before)])
    expect(plan.cancel).toEqual([1])
    expect(plan.schedule).toEqual([after])
  })

  // A this-occurrence move of a recurring event keeps the occurrence id.
  it('replaces a reminder whose time moved under the same id', () => {
    const before = wanted(1, 'Standup', '2026-10-05T07:50:00.000Z')
    const after = wanted(1, 'Standup', '2026-10-05T08:50:00.000Z')
    expect(planReminderQueue([after], [queued(before)]).schedule).toEqual([after])
  })

  it('replaces, once, an alarm queued by a build that wrote no signature', () => {
    const r = wanted(1, 'Standup')
    const plan = planReminderQueue([r], [{ id: 1, extra: { notificationId: 1 } as never }])
    expect(plan).toEqual({ cancel: [1], schedule: [r] })
    expect(planReminderQueue([r], [queued(r)])).toEqual({ cancel: [], schedule: [] })
  })
})
