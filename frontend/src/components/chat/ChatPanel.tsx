import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { chatApi } from '../../api/endpoints'
import type { ChatCard } from '../../api/types'
import { ErrorBanner, Spinner } from '../Feedback'
import { CardView } from './ChatCards'

interface Line { role: 'USER' | 'ASSISTANT'; content: string; cards: ChatCard[] }
/** decline: the confirmationId a card's "No" button declines (withdrawn server-side before the model runs). */
interface Outgoing { text: string; decline?: string }

const SUGGESTIONS = ["What's showing today?", 'Find Interstellar', 'Theatres in Chennai', 'Show my bookings']

export function BotAvatar({ className = 'h-7 w-7' }: { className?: string }) {
  return (
    <span aria-hidden className={`flex shrink-0 items-center justify-center rounded-full bg-rose-600/20 text-rose-300 ${className}`}>
      <svg viewBox="0 0 24 24" className="h-[62%] w-[62%]" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="4" y="7" width="16" height="11" rx="3" />
        <path d="M12 3v4M9 12h.01M15 12h.01M9.5 15h5" strokeLinecap="round" />
      </svg>
    </span>
  )
}

/**
 * The conversation. The bot runs on the backend's tools; this only renders replies and cards. Payment is never
 * started by the bot: the Confirm & Pay card is a real button the user presses.
 */
export function ChatPanel({ userId, onNavigate }: { userId: number; onNavigate?: () => void }) {
  const storageKey = `cinebot.chat.${userId}`
  const [sessionId, setSessionId] = useState<string | null>(() => localStorage.getItem(storageKey))
  const [lines, setLines] = useState<Line[]>([])
  const [input, setInput] = useState('')
  const endRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const send = useMutation({
    mutationFn: ({ text, decline }: Outgoing) => chatApi.send(sessionId, text, decline),
    onMutate: ({ text }) => setLines((l) => [...l, { role: 'USER', content: text, cards: [] }]),
    onSuccess: (reply) => {
      localStorage.setItem(storageKey, reply.conversationId)
      setSessionId(reply.conversationId)
      setLines((l) => [...l, { role: 'ASSISTANT', content: reply.response, cards: reply.cards }])
    },
  })

  const history = useQuery({
    queryKey: ['chat', sessionId],
    queryFn: () => chatApi.conversation(sessionId!),
    enabled: !!sessionId && lines.length === 0, // only to restore a saved conversation; never overwrite a live one
    retry: false,
    staleTime: Infinity, // local state is the source of truth once loaded
    gcTime: 0, // reopening the panel must load the messages sent since, not a cached copy
  })
  useEffect(() => {
    if (history.data) setLines(history.data.messages.map((m) => ({ role: m.role, content: m.content, cards: m.cards ?? [] })))
    if (history.error) { // stale id (e.g. database reset): start over
      localStorage.removeItem(storageKey)
      setSessionId(null)
    }
  }, [history.data, history.error, storageKey])
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [lines, send.isPending, send.error])
  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const say = (text: string, decline?: string) => !send.isPending && send.mutate({ text, decline })
  function submit(e: FormEvent) {
    e.preventDefault()
    if (input.trim()) say(input.trim())
    setInput('')
  }
  function retry() {
    const last = send.variables
    if (last === undefined) return
    setLines((l) => l.slice(0, -1)) // the failed message is added again by onMutate
    send.mutate(last)
  }
  function restart() {
    localStorage.removeItem(storageKey)
    setSessionId(null)
    setLines([])
    send.reset()
    inputRef.current?.focus()
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex-1 space-y-4 overflow-y-auto p-3">
        {history.isFetching && <Spinner label="Loading your conversation…" />}
        {lines.length === 0 && !history.isFetching && (
          <div className="flex flex-col items-center px-2 pt-6 text-center">
            <BotAvatar className="h-12 w-12" />
            <p className="mt-3 font-semibold text-slate-100">What would you like to watch?</p>
            <p className="mt-1 text-sm text-slate-400">I can find movies, showtimes and seats, and hold them for you.</p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              {SUGGESTIONS.map((c) => (
                <button key={c} className="rounded-full border border-slate-700 px-3 py-1.5 text-xs text-slate-200 transition hover:border-rose-500 hover:bg-rose-500/10" onClick={() => say(c)}>
                  {c}
                </button>
              ))}
            </div>
          </div>
        )}
        {lines.map((line, i) => line.role === 'USER' ? (
          <div key={i} className="flex justify-end motion-safe:animate-chat-in">
            <p className="max-w-[85%] whitespace-pre-line break-words rounded-2xl rounded-br-sm bg-rose-700 px-3 py-2 text-sm text-white">{line.content}</p>
          </div>
        ) : (
          <div key={i} className="flex gap-2 motion-safe:animate-chat-in">
            <BotAvatar />
            <div className="min-w-0 flex-1 space-y-2">
              <p className="w-fit max-w-full whitespace-pre-line break-words rounded-2xl rounded-tl-sm bg-slate-800/80 px-3 py-2 text-sm text-slate-100">{line.content}</p>
              {line.cards.map((card, j) => (
                <CardView key={j} card={card} say={say} onNavigate={onNavigate} live={i === lines.length - 1 && !send.isPending} />
              ))}
            </div>
          </div>
        ))}
        {send.isPending && (
          <div role="status" aria-label="CineBot is typing" className="flex gap-2">
            <BotAvatar />
            <span className="flex items-center gap-1 rounded-2xl rounded-tl-sm bg-slate-800/80 px-3 py-3">
              {['0ms', '150ms', '300ms'].map((delay) => (
                <span key={delay} className="h-1.5 w-1.5 rounded-full bg-slate-400 motion-safe:animate-bounce" style={{ animationDelay: delay }} />
              ))}
            </span>
          </div>
        )}
        {send.isError && (
          <div className="space-y-2">
            <ErrorBanner error={send.error} />
            <button className="btn-secondary px-3 py-1.5 text-xs" onClick={retry}>Try again</button>
          </div>
        )}
        <div ref={endRef} />
      </div>
      <form onSubmit={submit} className="border-t border-slate-800 p-3">
        <div className="flex items-center gap-2 rounded-full border border-slate-700 bg-slate-900 py-1 pl-4 pr-1 transition focus-within:border-rose-500">
          <input ref={inputRef} aria-label="Message" className="min-w-0 flex-1 bg-transparent text-sm text-slate-100 outline-none placeholder:text-slate-500"
                 value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask about movies, seats, bookings…" maxLength={1000} />
          <button aria-label="Send" className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-rose-600 text-white transition hover:bg-rose-500 disabled:cursor-not-allowed disabled:opacity-40"
                  disabled={send.isPending || !input.trim()}>
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M6 11l6-6 6 6" /></svg>
          </button>
        </div>
        <button type="button" className="mx-auto mt-2 block text-[11px] text-slate-500 transition hover:text-slate-300 disabled:opacity-50" disabled={send.isPending} onClick={restart}>
          New conversation
        </button>
      </form>
    </div>
  )
}
