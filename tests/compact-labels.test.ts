import { describe, it, expect } from 'vitest'
import { DateTime } from 'luxon'
import { compactHourLabel, compactWeekday, headerPeriod, periodTitle } from '../src/shared/compact-labels'

const weekLabel = (n: number): string => `week ${n}`

describe('compactHourLabel', () => {
  it('is the bare hour on a 24h clock', () => {
    expect(compactHourLabel(0, '24h')).toBe('0')
    expect(compactHourLabel(8, '24h')).toBe('8')
    expect(compactHourLabel(23, '24h')).toBe('23')
  })

  it('carries a one-letter period on a 12h clock', () => {
    expect(compactHourLabel(0, '12h')).toBe('12a')
    expect(compactHourLabel(9, '12h')).toBe('9a')
    expect(compactHourLabel(12, '12h')).toBe('12p')
    expect(compactHourLabel(21, '12h')).toBe('9p')
  })
})

describe('compactWeekday', () => {
  // 2026-09-28 is a Monday.
  const monday = DateTime.fromISO('2026-09-28')

  it('uses two letters in English', () => {
    expect(compactWeekday(monday, 'en')).toBe('Mo')
    expect(compactWeekday(monday.plus({ days: 6 }), 'en')).toBe('Su')
  })

  it('uses the Vietnamese T2 … CN forms rather than Luxon\'s "Th 2"', () => {
    expect(compactWeekday(monday, 'vi')).toBe('T2')
    expect(compactWeekday(monday.plus({ days: 5 }), 'vi')).toBe('T7')
    expect(compactWeekday(monday.plus({ days: 6 }), 'vi')).toBe('CN')
  })
})

describe('periodTitle', () => {
  const start = DateTime.fromISO('2026-09-28')
  const end = DateTime.fromISO('2026-10-04')

  it('names both months for a week that straddles them, with the week number below', () => {
    expect(periodTitle('week', start, end, 'en', weekLabel)).toEqual({ title: 'Sep / Oct', subtitle: 'week 40' })
  })

  it('names one month in full when the week sits inside it', () => {
    const s = DateTime.fromISO('2026-10-05')
    expect(periodTitle('week', s, s.plus({ days: 6 }), 'en', weekLabel)).toEqual({
      title: 'October',
      subtitle: 'week 41'
    })
  })

  it('capitalises Vietnamese month names', () => {
    const s = DateTime.fromISO('2026-10-05')
    expect(periodTitle('week', s, s.plus({ days: 6 }), 'vi', weekLabel).title).toBe('Tháng 10')
  })

  it('gives the month and year for month view, and the year alone for year view', () => {
    const anchor = DateTime.fromISO('2026-10-02')
    expect(periodTitle('month', anchor, anchor, 'en', weekLabel)).toEqual({ title: 'October', subtitle: '2026' })
    expect(periodTitle('year', anchor, anchor, 'en', weekLabel)).toEqual({ title: '2026' })
  })
})

describe('headerPeriod', () => {
  it('titles a week view after the days it shows', () => {
    expect(headerPeriod('week', DateTime.fromISO('2026-10-02T12:00'), 'en', weekLabel)).toEqual({
      title: 'Sep / Oct',
      subtitle: 'week 40'
    })
  })

  // The month grid for October 2026 opens on Monday 28 September.
  it('titles a month view after the anchor month, not the first day on the grid', () => {
    expect(headerPeriod('month', DateTime.fromISO('2026-10-02T12:00'), 'en', weekLabel)).toEqual({
      title: 'October',
      subtitle: '2026'
    })
  })
})
