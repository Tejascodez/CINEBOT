import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { BotAvatar, ChatPanel } from './ChatPanel'

/**
 * Floating bot launcher, bottom-right on every page. Lives in the layout, so the conversation survives
 * navigation (browse a movie, come back to the chat). Esc or the button closes it.
 */
export function ChatWidget() {
  const { user, ready } = useAuth()
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <div className="fixed bottom-5 right-5 z-50 flex flex-col items-end gap-3">
      {open && (
        <section
          role="dialog"
          aria-label="CineBot chat"
          className="flex h-[min(40rem,calc(100dvh-7rem))] w-[calc(100vw-2.5rem)] origin-bottom-right flex-col overflow-hidden rounded-2xl border border-slate-700 bg-slate-950 shadow-2xl shadow-black/60 motion-safe:animate-chat-in sm:w-[26rem]"
        >
          <header className="flex items-center gap-2 border-b border-slate-800 bg-slate-900 px-4 py-3">
            <BotAvatar className="h-9 w-9" />
            <div className="leading-tight">
              <p className="font-semibold">CineBot</p>
              <p className="text-xs text-slate-400">Your booking assistant</p>
            </div>
            <button className="btn-ghost ml-auto px-2 py-1" aria-label="Close chat" onClick={() => setOpen(false)}>✕</button>
          </header>
          {!ready ? null : user ? (
            <ChatPanel userId={user.id} onNavigate={() => setOpen(false)} />
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center text-sm text-slate-400">
              <p>Sign in to chat with CineBot and book tickets in a conversation.</p>
              <button className="btn-primary" onClick={() => {
                setOpen(false)
                navigate('/login', { state: { from: location.pathname + location.search } })
              }}>Sign in</button>
            </div>
          )}
        </section>
      )}
      <button
        aria-label={open ? 'Close chat' : 'Open chat'}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex h-14 w-14 items-center justify-center rounded-full bg-rose-600 text-white shadow-lg shadow-rose-950/60 transition hover:scale-105 hover:bg-rose-500"
      >
        {open ? (
          <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="2"><path d="M6 6l12 12M18 6L6 18" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" className="h-7 w-7" fill="none" stroke="currentColor" strokeWidth="1.8">
            <rect x="4" y="7" width="16" height="11" rx="3" />
            <path d="M12 3v4M9 12h.01M15 12h.01M9.5 15h5" strokeLinecap="round" />
          </svg>
        )}
      </button>
    </div>
  )
}
