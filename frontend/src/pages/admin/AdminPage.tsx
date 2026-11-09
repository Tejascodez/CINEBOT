import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { adminApi, catalogApi } from '../../api/endpoints'
import { ErrorBanner } from '../../components/Feedback'
import { useCity } from '../../lib/city'
import { istDate, money, time } from '../../lib/format'

/** Operations dashboard. Creating theatres / screens / shows stays in Swagger UI for now (/swagger-ui.html). */
export function AdminPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Admin</h1>
      <div className="grid gap-6 lg:grid-cols-2">
        <SalesPanel />
        <OutboxPanel />
        <TicketPanel />
        <SyncPanel />
        <FaultsPanel />
      </div>
    </div>
  )
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="card space-y-3"><h2 className="font-semibold">{title}</h2>{children}</section>
}

function SalesPanel() {
  const [city] = useCity()
  const [showId, setShowId] = useState<number | null>(null)
  const shows = useQuery({ queryKey: ['admin-shows', city], queryFn: () => catalogApi.shows({ city, date: istDate(0) }) })
  const stats = useQuery({ queryKey: ['stats', showId], queryFn: () => adminApi.showStats(showId!), enabled: showId != null })
  return (
    <Panel title={`Sales today · ${city}`}>
      <select aria-label="Show" className="input" value={showId ?? ''} onChange={(e) => setShowId(Number(e.target.value) || null)}>
        <option value="">Choose a show…</option>
        {shows.data?.map((s) => <option key={s.id} value={s.id}>{time(s.startTime)} · {s.movieTitle} · {s.theatreName}</option>)}
      </select>
      {stats.data && (
        <dl className="grid grid-cols-4 gap-2 text-center">
          {[['Bookings', stats.data.bookingsConfirmed], ['Seats', stats.data.seatsSold],
            ['Revenue', money(stats.data.revenuePaise)], ['Refunds', stats.data.refunds]].map(([k, v]) => (
            <div key={k} className="rounded-lg bg-slate-800 p-2"><dt className="text-xs text-slate-400">{k}</dt><dd className="font-bold">{v}</dd></div>
          ))}
        </dl>
      )}
      <p className="text-xs text-slate-500">Eventually consistent: updated by the outbox relay within about a second.</p>
    </Panel>
  )
}

function OutboxPanel() {
  const client = useQueryClient()
  const summary = useQuery({ queryKey: ['outbox'], queryFn: adminApi.outboxSummary, refetchInterval: 5000 })
  const dead = useQuery({ queryKey: ['outbox-dead'], queryFn: adminApi.deadEvents })
  const retry = useMutation({
    mutationFn: adminApi.retryEvent,
    onSuccess: () => client.invalidateQueries({ queryKey: ['outbox-dead'] }),
  })
  return (
    <Panel title="Event outbox">
      {summary.data && <p className="text-sm">{summary.data.pending} pending · {summary.data.done} delivered ·{' '}
        <span className={summary.data.dead ? 'text-rose-400' : ''}>{summary.data.dead} dead</span></p>}
      <ul className="space-y-2 text-xs">
        {dead.data?.map((e) => (
          <li key={e.id} className="rounded bg-slate-800 p-2">
            <p className="font-mono">{e.type} · booking {e.aggregateId} · {e.attempts} attempts</p>
            <p className="text-rose-300">{e.lastError}</p>
            <button className="btn-secondary mt-1 text-xs" onClick={() => retry.mutate(e.id)}>Retry</button>
          </li>
        ))}
      </ul>
    </Panel>
  )
}

function TicketPanel() {
  const [qr, setQr] = useState('')
  const verify = useMutation({ mutationFn: adminApi.verifyTicket })
  return (
    <Panel title="Verify a ticket (gate scan)">
      <div className="flex gap-2">
        <input aria-label="QR payload" className="input font-mono text-xs" placeholder="CBT1.…" value={qr} onChange={(e) => setQr(e.target.value)} />
        <button className="btn-secondary" disabled={!qr} onClick={() => verify.mutate(qr)}>Check</button>
      </div>
      {verify.data && (
        <p className={`text-sm ${verify.data.valid ? 'text-emerald-300' : 'text-rose-300'}`}>
          {verify.data.valid ? `Valid: ${verify.data.reference} · seats ${verify.data.seats.join(', ')}` : `Rejected: ${verify.data.reason}`}
        </p>
      )}
      <ErrorBanner error={verify.error} />
    </Panel>
  )
}

function SyncPanel() {
  const client = useQueryClient()
  const runs = useQuery({ queryKey: ['sync-runs'], queryFn: adminApi.syncRuns })
  const sync = useMutation({ mutationFn: adminApi.syncMovies, onSettled: () => client.invalidateQueries({ queryKey: ['sync-runs'] }) })
  return (
    <Panel title="TMDB movie sync">
      <button className="btn-secondary" disabled={sync.isPending} onClick={() => sync.mutate()}>
        {sync.isPending ? 'Syncing…' : 'Import now playing'}
      </button>
      <ErrorBanner error={sync.error} />
      <ul className="text-xs text-slate-400">
        {runs.data?.slice(0, 5).map((r) => <li key={r.id}>#{r.id} {r.status} · {r.moviesUpserted} movies {r.error && `· ${r.error}`}</li>)}
      </ul>
    </Panel>
  )
}

function FaultsPanel() {
  const client = useQueryClient()
  const faults = useQuery({ queryKey: ['faults'], queryFn: adminApi.faults, retry: false })
  const set = useMutation({ mutationFn: adminApi.setFaults, onSuccess: (f) => client.setQueryData(['faults'], f) })
  if (faults.error) return null // fault injection disabled (production)
  return (
    <Panel title="Fault injection (dev)">
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={faults.data?.crashAfterCapture ?? false} onChange={(e) => set.mutate(e.target.checked)} />
        Crash after payment capture, before confirmation
      </label>
      <p className="text-xs text-slate-500">Pay while this is on: the booking stays pending, then recovers by webhook retry or the reconciler.</p>
    </Panel>
  )
}
