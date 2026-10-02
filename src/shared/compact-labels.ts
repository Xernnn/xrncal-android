import { DateTime } from 'luxon'
import type { TimeFormatPref } from './time-format'
import { getVisibleRange, type CalendarViewType } from './visible-range'

/**
 * Labels for the phone layout (DisplayPreferences.compact), where a week
 * column is ~55px wide and the hour gutter ~28px.
 */

/** The hour ruler's label: `8` / `14` on a 24h clock, `8a` / `2p` on a 12h one. */
export function compactHourLabel(hour: number, pref: TimeFormatPref): string {
  if (pref === '24h') return String(hour)
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  return `${h12}${hour < 12 ? 'a' : 'p'}`
}

// Luxon's own short weekdays do not shorten well: Vietnamese `ccc` is "Th 2",
// which is wider than the day number it sits beside. These are the forms a
// Vietnamese calendar prints.
const VI_WEEKDAYS = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']

/** Two-character weekday for a week column header: `Mo`, `Tu` … / `T2`, `T3` … `CN`. */
export function compactWeekday(day: DateTime, locale: string): string {
  if (locale === 'vi') return VI_WEEKDAYS[day.weekday - 1]
  return day.setLocale(locale).toFormat('ccc').slice(0, 2)
}

export interface PeriodTitle {
  title: string
  subtitle?: string
}

function capitalise(s: string): string {
  return s.charAt(0).toLocaleUpperCase() + s.slice(1)
}

/**
 * The phone header's two-line title for a visible range, e.g. `Sep / Oct` over
 * `week 40` for a week that straddles two months.
 *
 * `start` and `end` are the first and last *day* shown; `weekLabel` turns a
 * week number into the subtitle so the caller's translation decides the
 * wording ("week 40", "tuần 40").
 */
export function periodTitle(
  view: CalendarViewType,
  start: DateTime,
  end: DateTime,
  locale: string,
  weekLabel: (week: number) => string
): PeriodTitle {
  const s = start.setLocale(locale)
  const e = end.setLocale(locale)

  switch (view) {
    case 'year':
      return { title: String(s.year) }
    case 'month':
    case 'list':
      return { title: capitalise(s.toFormat('LLLL')), subtitle: String(s.year) }
    case 'day':
    case 'week': {
      const title = s.hasSame(e, 'month')
        ? capitalise(s.toFormat('LLLL'))
        : `${capitalise(s.toFormat('LLL'))} / ${capitalise(e.toFormat('LLL'))}`
      return { title, subtitle: weekLabel(s.weekNumber) }
    }
  }
}

/**
 * periodTitle() for what a view is showing. Week and day name the days on
 * screen; month, list and year name the anchor's period, because a month grid
 * opens on the Monday before the 1st and would otherwise be titled after the
 * previous month.
 */
export function headerPeriod(
  view: CalendarViewType,
  anchorDate: DateTime,
  locale: string,
  weekLabel: (week: number) => string
): PeriodTitle {
  if (view !== 'week' && view !== 'day') return periodTitle(view, anchorDate, anchorDate, locale, weekLabel)
  const range = getVisibleRange(anchorDate, view, locale)
  const start = DateTime.fromISO(range.startUtc).toLocal()
  const end = DateTime.fromISO(range.endUtc).toLocal()
  return periodTitle(view, start, end, locale, weekLabel)
}
