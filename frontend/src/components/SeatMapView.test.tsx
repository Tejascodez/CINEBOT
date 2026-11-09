import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SeatMap } from '../api/types'
import { toggleSeat } from '../pages/SeatSelectionPage'
import { SeatMapView } from './SeatMapView'

const map: SeatMap = {
  showId: 1, totalSeats: 3, availableSeats: 2,
  rows: [{ label: 'A', seats: [
    { showSeatId: 11, label: 'A1', number: 1, type: 'REGULAR', pricePaise: 20000, status: 'AVAILABLE' },
    { showSeatId: 12, label: 'A2', number: 2, type: 'REGULAR', pricePaise: 20000, status: 'BOOKED' },
    { showSeatId: 13, label: 'A3', number: 3, type: 'PREMIUM', pricePaise: 35000, status: 'AVAILABLE' },
  ] }],
}

describe('SeatMapView', () => {
  it('lets only available seats be chosen', async () => {
    const onToggle = vi.fn()
    render(<SeatMapView map={map} selected={[13]} onToggle={onToggle} />)

    await userEvent.click(screen.getByRole('button', { name: /A1 regular/ }))
    expect(onToggle).toHaveBeenCalledWith(expect.objectContaining({ label: 'A1' }))

    expect(screen.getByRole('button', { name: /A2 regular .* booked/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /A3 premium/ })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows a price legend per seat type', () => {
    render(<SeatMapView map={map} selected={[]} onToggle={() => {}} />)
    expect(screen.getByText('premium ₹350')).toBeInTheDocument()
  })
})

describe('toggleSeat', () => {
  it('adds, removes and respects the maximum', () => {
    expect(toggleSeat([], 1)).toEqual([1])
    expect(toggleSeat([1, 2], 1)).toEqual([2])
    expect(toggleSeat([1, 2], 3, 2)).toEqual([1, 2]) // full: unchanged
  })
})
