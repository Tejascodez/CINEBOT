import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { AuthProvider } from '../../auth/AuthContext'
import { ChatWidget } from './ChatWidget'

function renderWidget() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter><ChatWidget /></MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('ChatWidget', () => {
  beforeEach(() => localStorage.clear())

  it('is a closed bubble until clicked, then opens the chat panel', async () => {
    renderWidget()
    expect(screen.queryByRole('dialog', { name: 'CineBot chat' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Open chat' }))

    expect(await screen.findByRole('dialog', { name: 'CineBot chat' })).toBeInTheDocument()
    // the launcher now acts as the close button and reports its state
    const launcher = screen.getAllByRole('button', { name: 'Close chat' }).find((b) => b.hasAttribute('aria-expanded'))
    expect(launcher).toHaveAttribute('aria-expanded', 'true')
  })

  it('asks signed-out visitors to sign in', async () => {
    renderWidget()
    await userEvent.click(screen.getByRole('button', { name: 'Open chat' }))
    expect(await screen.findByText(/Sign in to chat with CineBot/)).toBeInTheDocument()
  })

  it('closes with Escape', async () => {
    renderWidget()
    await userEvent.click(screen.getByRole('button', { name: 'Open chat' }))
    await screen.findByRole('dialog')

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
