/**
 * Reconciling the queue of OS-scheduled reminders with the one the database
 * wants. Pure, so it is tested without a device (tests/android-reminder-queue).
 *
 * A notification id is a hash of the occurrence id, so it survives any edit
 * that keeps the occurrence: a new title or location, a this-occurrence move
 * of a recurring event (an exception keeps its original start), a language
 * switch. Comparing ids alone therefore left every such alarm queued with its
 * old text - or firing at its old time. Each alarm carries a signature of what
 * it says and when in its `extra`, and a differing signature replaces it.
 */

export interface WantedReminder {
  id: number
  title: string
  body: string
  at: Date
  /** The occurrence's start, so a tap can open its day. */
  startUtc: string
}

/** The fields of a pending LocalNotification this module reads. */
export interface PendingReminder {
  id: number
  extra?: { sig?: unknown } | null
}

export function reminderSignature(r: Pick<WantedReminder, 'title' | 'body' | 'at'>): string {
  return `${r.at.toISOString()}|${r.title}|${r.body}`
}

export function planReminderQueue(
  wanted: WantedReminder[],
  pending: PendingReminder[]
): { cancel: number[]; schedule: WantedReminder[] } {
  const wantedById = new Map(wanted.map((r) => [r.id, r]))
  const pendingSig = new Map(pending.map((p) => [p.id, p.extra?.sig]))

  // An alarm queued by a build that wrote no signature reads as changed, so
  // it is replaced once and carries one from then on.
  const changed = (r: WantedReminder): boolean => pendingSig.get(r.id) !== reminderSignature(r)

  const cancel = pending
    .filter((p) => {
      const want = wantedById.get(p.id)
      return !want || changed(want)
    })
    .map((p) => p.id)

  const schedule = wanted.filter((r) => !pendingSig.has(r.id) || changed(r))

  return { cancel, schedule }
}
