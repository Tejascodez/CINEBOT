import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { catalogApi } from '../api/endpoints'
import type { Show } from '../api/types'
import { Empty, ErrorBanner, Spinner } from '../components/Feedback'
import { Poster } from '../components/Poster'
import { useCity } from '../lib/city'
import { dayLabel, istDate, languageName, money, runtime, time } from '../lib/format'

const DAYS = [0, 1, 2]

export function MovieDetailPage() {
  const id = Number(useParams().id)
  const [city] = useCity()
  const [date, setDate] = useState(istDate(0))
  const movie = useQuery({ queryKey: ['movie', id], queryFn: () => catalogApi.movie(id) })
  const shows = useQuery({
    queryKey: ['shows', id, city, date],
    queryFn: () => catalogApi.shows({ movieId: id, city, date }),
    refetchInterval: 30_000, // seat counts change as people book
  })

  if (movie.isPending) return <Spinner />
  if (movie.error) return <ErrorBanner error={movie.error} />
  const m = movie.data

  const byTheatre = new Map<string, Show[]>()
  shows.data?.forEach((s) => byTheatre.set(s.theatreName, [...(byTheatre.get(s.theatreName) ?? []), s]))

  return (
    <div className="grid gap-8 md:grid-cols-[220px_1fr]">
      <Poster url={m.posterUrl} title={m.title} />
      <div className="space-y-6">
        <div>
          <h1 className="text-3xl font-bold">{m.title}</h1>
          <p className="mt-1 text-sm text-slate-400">
            {[languageName(m.language), runtime(m.runtimeMinutes), m.genres.map((g) => g.name).join(', '),
              m.rating != null ? `★ ${m.rating}` : null].filter(Boolean).join(' · ')}
          </p>
          {m.overview && <p className="mt-3 max-w-2xl text-slate-300">{m.overview}</p>}
        </div>

        <div>
          <h2 className="mb-3 text-lg font-semibold">Showtimes in {city}</h2>
          <div className="mb-4 flex gap-2" role="tablist">
            {DAYS.map((d) => {
              const value = istDate(d)
              return (
                <button key={value} role="tab" aria-selected={date === value}
                        className={date === value ? 'btn-primary' : 'btn-secondary'} onClick={() => setDate(value)}>
                  {d === 0 ? 'Today' : d === 1 ? 'Tomorrow' : dayLabel(value)}
                </button>
              )
            })}
          </div>
          <ErrorBanner error={shows.error} />
          {shows.isPending ? <Spinner /> : byTheatre.size === 0 ? <Empty>No more shows on this day in {city}.</Empty> : (
            <ul className="space-y-3">
              {[...byTheatre].map(([theatre, list]) => (
                <li key={theatre} className="card">
                  <p className="font-semibold">{theatre}</p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {list.map((s) => (
                      <Link key={s.id} to={`/shows/${s.id}/seats`}
                            className={`rounded-lg border px-3 py-2 text-sm ${s.availableSeats === 0
                              ? 'pointer-events-none border-slate-800 text-slate-600'
                              : 'border-emerald-700 text-emerald-300 hover:bg-emerald-950'}`}>
                        <span className="font-semibold">{time(s.startTime)}</span>
                        <span className="ml-2 text-xs text-slate-400">{s.format} · from {money(s.minPricePaise)}</span>
                        <span className="block text-xs text-slate-500">
                          {s.availableSeats === 0 ? 'Sold out' : `${s.availableSeats} seats left`} · {s.screenName}
                        </span>
                      </Link>
                    ))}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}
