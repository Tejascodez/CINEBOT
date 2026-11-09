import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { bookingApi, notificationApi } from '../api/endpoints'
import { Empty, ErrorBanner, Spinner } from '../components/Feedback'
import { StatusBadge } from '../components/StatusBadge'
import { dateTime, money } from '../lib/format'

export function BookingsPage() {
  const bookings = useQuery({ queryKey: ['bookings'], queryFn: () => bookingApi.mine() })
  const inbox = useQuery({ queryKey: ['notifications'], queryFn: notificationApi.mine })

  return (
    <div className="grid gap-8 lg:grid-cols-[1fr_320px]">
      <section className="space-y-3">
        <h1 className="text-2xl font-bold">My bookings</h1>
        <ErrorBanner error={bookings.error} />
        {bookings.isPending ? <Spinner /> : bookings.data?.content.length === 0 ? <Empty>No bookings yet.</Empty> : (
          <ul className="space-y-3">
            {bookings.data?.content.map((b) => (
              <li key={b.id}>
                <Link to={`/bookings/${b.id}`} className="card flex flex-wrap items-center gap-3 hover:border-slate-600">
                  <div>
                    <p className="font-semibold">{b.show?.movieTitle}</p>
                    <p className="text-xs text-slate-400">
                      {b.show && `${b.show.theatreName} · ${dateTime(b.show.startTime)} · `}{b.seats.map((s) => s.label).join(', ')}
                    </p>
                  </div>
                  <span className="ml-auto text-sm">{money(b.totalPaise)}</span>
                  <StatusBadge status={b.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <aside className="space-y-3">
        <h2 className="text-lg font-semibold">Inbox</h2>
        {inbox.data?.length === 0 && <p className="text-sm text-slate-500">Emails we send you appear here.</p>}
        <ul className="space-y-2">
          {inbox.data?.map((n) => (
            <li key={n.id} className="card text-sm">
              <p className="font-semibold">{n.subject}</p>
              <p className="mt-1 whitespace-pre-line break-all text-xs text-slate-400">{n.body}</p>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  )
}
