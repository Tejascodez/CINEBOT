import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chatApi } from '../../api/endpoints'
import { ChatPanel } from './ChatPanel'

function renderPanel() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter><ChatPanel userId={1} /></MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ChatPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    // Some browsers return a Promise here; an effect that returns it crashes React with "destroy is not a function".
    Element.prototype.scrollIntoView = vi.fn(() => Promise.resolve()) as unknown as typeof Element.prototype.scrollIntoView
  })
  afterEach(() => vi.restoreAllMocks())

  it('starts a conversation on the first message and continues it on the next', async () => {
    const send = vi.spyOn(chatApi, 'send').mockResolvedValue({ conversationId: 'c-1', response: 'Which city?', cards: [] })
    renderPanel()

    await userEvent.type(screen.getByLabelText('Message'), 'Find Interstellar{Enter}')
    expect(await screen.findByText('Which city?')).toBeInTheDocument()
    expect(send).toHaveBeenLastCalledWith(null, 'Find Interstellar', undefined)
    expect(localStorage.getItem('cinebot.chat.1')).toBe('c-1')

    await userEvent.type(screen.getByLabelText('Message'), 'Chennai{Enter}')
    expect(send).toHaveBeenLastCalledWith('c-1', 'Chennai', undefined)
    expect(screen.getByText('Find Interstellar')).toBeInTheDocument()
  })

  it('restores a saved conversation', async () => {
    localStorage.setItem('cinebot.chat.1', 'c-1')
    vi.spyOn(chatApi, 'conversation').mockResolvedValue({
      conversationId: 'c-1', createdAt: '2026-11-05T10:00:00Z',
      messages: [{ role: 'ASSISTANT', content: 'Welcome back', cards: null, createdAt: '2026-11-05T10:00:00Z' }],
    })
    renderPanel()
    expect(await screen.findByText('Welcome back')).toBeInTheDocument()
  })

  it('declines a confirmation card by its id, so the backend withdraws it', async () => {
    const hold = { confirmationId: 'K7M2Q9XA', movie: 'Interstellar', theatre: 'PVR', screen: 'S1', showTime: 'Sat 6 PM',
      total: '₹200', holdDurationMinutes: 10, seats: [{ seat: 'A1', type: 'REGULAR', price: '₹200' }] }
    const send = vi.spyOn(chatApi, 'send')
      .mockResolvedValueOnce({ conversationId: 'c-1', response: 'Confirm?', cards: [{ type: 'HOLD_CONFIRMATION_REQUIRED', data: hold }] })
      .mockResolvedValueOnce({ conversationId: 'c-1', response: 'OK, nothing held.', cards: [] })
    renderPanel()

    await userEvent.type(screen.getByLabelText('Message'), 'Hold A1{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }))
    expect(await screen.findByText('OK, nothing held.')).toBeInTheDocument()
    expect(send).toHaveBeenLastCalledWith('c-1', "No, don't hold them", 'K7M2Q9XA')
  })

  it('only offers yes/no on the latest confirmation card, and retries a failed message once', async () => {
    const hold = { confirmationId: 'K7M2Q9XA', movie: 'Interstellar', theatre: 'PVR', screen: 'S1', showTime: 'Sat 6 PM', total: '₹200', holdDurationMinutes: 10,
      seats: [{ seat: 'A1', type: 'REGULAR', price: '₹200' }] }
    const send = vi.spyOn(chatApi, 'send')
      .mockResolvedValueOnce({ conversationId: 'c-1', response: 'Confirm?', cards: [{ type: 'HOLD_CONFIRMATION_REQUIRED', data: hold }] })
      .mockRejectedValueOnce(new Error('down'))
      .mockResolvedValueOnce({ conversationId: 'c-1', response: 'Held!', cards: [] })
    renderPanel()

    await userEvent.type(screen.getByLabelText('Message'), 'Hold A1{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: 'Hold seats' }))
    expect(send).toHaveBeenLastCalledWith('c-1', 'Yes, hold these seats', undefined)

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Held!')).toBeInTheDocument()
    expect(send).toHaveBeenCalledTimes(3)
    expect(screen.getAllByText('Yes, hold these seats')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Hold seats' })).not.toBeInTheDocument()
  })

  it('renders the agent seat map card and asks for the hold in plain language', async () => {
    const seatMap = {
      showId: 7, totalSeats: 1, availableSeats: 1,
      rows: [{ label: 'A', seats: [{ showSeatId: 11, label: 'A1', number: 1, type: 'REGULAR', pricePaise: 20000, status: 'AVAILABLE' }] }],
    }
    const send = vi.spyOn(chatApi, 'send').mockResolvedValue({
      conversationId: 'c-1', response: 'Pick your seats', cards: [{ type: 'SEAT_MAP', data: { show: { id: 7 }, seatMap } }],
    })
    renderPanel()

    await userEvent.type(screen.getByLabelText('Message'), 'Show seats{Enter}')
    await screen.findByText('Pick your seats')
    await userEvent.click(screen.getByRole('button', { name: /A1/ }))
    await userEvent.click(screen.getByRole('button', { name: /^Hold A1/ }))

    expect(send).toHaveBeenLastCalledWith('c-1', 'Hold seats A1 for show 7', undefined)
  })
})
