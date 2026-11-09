import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { catalogApi } from '../api/endpoints'
import { Empty, ErrorBanner, Spinner } from '../components/Feedback'
import { Poster } from '../components/Poster'
import { useCity } from '../lib/city'
import { languageName } from '../lib/format'

export function HomePage() {
  const [city] = useCity()
  const [genreId, setGenreId] = useState<number | undefined>()
  const [query, setQuery] = useState('')
  const searching = query.trim().length >= 2

  const genres = useQuery({ queryKey: ['genres'], queryFn: catalogApi.genres, staleTime: 3_600_000 })
  const movies = useQuery({
    queryKey: searching ? ['search', query.trim()] : ['movies', city, genreId],
    queryFn: () => (searching ? catalogApi.search(query.trim()) : catalogApi.movies({ city, genreId, size: 30 })),
  })

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <h1 className="text-2xl font-bold">{searching ? 'Search results' : `Now showing in ${city}`}</h1>
          <p className="text-sm text-slate-400">Pick a movie to see showtimes, or ask the assistant.</p>
        </div>
        <input
          aria-label="Search movies"
          className="input ml-auto max-w-xs"
          placeholder="Search movies (typos are fine)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {!searching && (
        <div className="flex flex-wrap gap-2">
          <button className={genreId === undefined ? 'btn-primary' : 'btn-secondary'} onClick={() => setGenreId(undefined)}>All</button>
          {genres.data?.map((g) => (
            <button key={g.id} className={genreId === g.id ? 'btn-primary' : 'btn-secondary'} onClick={() => setGenreId(g.id)}>
              {g.name}
            </button>
          ))}
        </div>
      )}

      <ErrorBanner error={movies.error} />
      {movies.isPending ? <Spinner /> : movies.data?.content.length === 0 ? (
        <Empty>{searching ? 'No movies match that search.' : `Nothing is scheduled in ${city} for this filter yet.`}</Empty>
      ) : (
        <ul className="grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-5">
          {movies.data?.content.map((m) => (
            <li key={m.id}>
              <Link to={`/movies/${m.id}`} className="group block">
                <Poster url={m.posterUrl} title={m.title} className="transition group-hover:scale-[1.02]" />
                <p className="mt-2 font-semibold leading-tight">{m.title}</p>
                <p className="text-xs text-slate-400">{languageName(m.language)} · {m.genres.slice(0, 2).join(', ')}</p>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
