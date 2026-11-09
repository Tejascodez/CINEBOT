import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { bookingApi, catalogApi } from '../api/endpoints'
import type { SeatView } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorBanner, Spinner } from '../components/Feedback'
import { SeatMapView } from '../components/SeatMapView'
import { dateTime, money } from '../lib/format'

export const MAX_SEATS = 10

/** Pure selection rule, unit-tested: toggles a seat, refusing a new seat past the limit. */
export function toggleSeat(selected: number[], seatId: number, max = MAX_SEATS): number[] {
  if (selected.includes(seatId)) return selected.filter((id) => id !== seatId)
  return selected.length >= max ? selected : [...selected, seatId]
}

export function SeatSelectionPage() {
  const showId = Number(useParams().showId)
  const navigate = useNavigate()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<number[]>([])
  const [taken, setTaken] = useState<number[]>([])

  const show = useQuery({ queryKey: ['show', showId], queryFn: () => catalogApi.show(showId) })
  const seats = useQuery({ queryKey: ['seats', showId], queryFn: () => catalogApi.seats(showId), refetchInterval: 10_000 })

  const hold = useMutation({
    mutationFn: async () => {
      const lock = await bookingApi.lockSeats(showId, selected)
      return bookingApi.create(lock.lockId)
    },
    onSuccess: (booking) => navigate(`/bookings/${booking.id}`),
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'SEATS_UNAVAILABLE') {
        const ids = (err.problem.unavailableSeatIds as number[] | undefined) ?? []
        setTaken(ids)
        setSelected((s) => s.filter((id) => !ids.includes(id)))
        void queryClient.invalidateQueries({ queryKey: ['seats', showId] })
      }
    },
  })

  const release = useMutation({
    mutationFn: (lockId: string) => bookingApi.releaseLock(lockId),
    onSuccess: () => hold.reset(),
  })

  if (show.isPending || seats.isPending) return <Spinner />
  if (show.error || seats.error) return <ErrorBanner error={show.error ?? seats.error} />

  const all: SeatView[] = seats.data.rows.flatMap((r) => r.seats)
  const chosen = all.filter((s) => selected.includes(s.showSeatId))
  const total = chosen.reduce((sum, s) => sum + s.pricePaise, 0)
  const holdError = hold.error instanceof ApiError ? hold.error : null

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{show.data.movieTitle}</h1>
        <p className="text-sm text-slate-400">{show.data.theatreName} · {show.data.screenName} · {dateTime(show.data.startTime)}</p>
      </div>

      <div className="card">
        <SeatMapView map={seats.data} selected={selected} highlight={taken}
                     onToggle={(seat) => setSelected((s) => toggleSeat(s, seat.showSeatId))} />
      </div>

      {holdError?.code === 'ACTIVE_LOCK_EXISTS' ? (
        <div className="card flex flex-wrap items-center gap-3 border-amber-800">
          <p className="text-sm text-amber-200">You're already holding seats for this show.</p>
          <button className="btn-secondary" disabled={release.isPending}
                  onClick={() => release.mutate(String(holdError.problem.lockId))}>Release them</button>
          <button className="btn-ghost" onClick={() => navigate('/bookings')}>Go to my bookings</button>
          <ErrorBanner error={release.error} />
        </div>
      ) : <ErrorBanner error={hold.error} />}

      {/* right margin keeps the Hold button clear of the floating chat bubble */}
      <div className="sticky bottom-4 mr-20 card flex flex-wrap items-center gap-4">
        <div className="text-sm">
          {chosen.length === 0 ? <span className="text-slate-400">Select up to {MAX_SEATS} seats</span> : (
            <><span className="font-semibold">{chosen.map((s) => s.label).join(', ')}</span>
              <span className="ml-3 text-slate-400">{chosen.length} seat{chosen.length > 1 ? 's' : ''}</span></>
          )}
        </div>
        <span className="ml-auto text-lg font-bold">{money(total)}</span>
        {user ? (
          <button className="btn-primary" disabled={chosen.length === 0 || hold.isPending} onClick={() => hold.mutate()}>
            {hold.isPending ? 'Holding…' : 'Hold seats'}
          </button>
        ) : (
          <button className="btn-primary" onClick={() => navigate('/login', { state: { from: `/shows/${showId}/seats` } })}>
            Sign in to book
          </button>
        )}
      </div>
    </div>
  )
}
