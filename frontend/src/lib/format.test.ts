import { describe, expect, it } from 'vitest'
import { istDate, money, time } from './format'

describe('format', () => {
  it('formats paise as rupees', () => {
    expect(money(20000)).toBe('₹200')
    expect(money(12345600)).toBe('₹1,23,456') // Indian digit grouping
    expect(money(null)).toBe('—')
  })

  it('shows times in IST whatever the browser zone', () => {
    expect(time('2026-11-05T20:45:00+05:30')).toMatch(/8:45\s?pm/i)
  })

  it('computes the IST date, which differs from UTC late in the evening', () => {
    // 20:00 UTC on 4 Nov = 01:30 IST on 5 Nov
    expect(istDate(0, new Date('2026-11-04T20:00:00Z'))).toBe('2026-11-05')
    expect(istDate(1, new Date('2026-11-04T20:00:00Z'))).toBe('2026-11-06')
  })
})
