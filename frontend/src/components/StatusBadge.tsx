import type { BookingStatus } from '../api/types'

const STYLES: Record<BookingStatus, string> = {
  PENDING: 'bg-amber-900/60 text-amber-200',
  PAYMENT_PENDING: 'bg-amber-900/60 text-amber-200',
  CONFIRMED: 'bg-emerald-900/60 text-emerald-200',
  PAYMENT_FAILED: 'bg-rose-900/60 text-rose-200',
  EXPIRED: 'bg-slate-800 text-slate-300',
  CANCELLED: 'bg-slate-800 text-slate-300',
  REFUND_PENDING: 'bg-sky-900/60 text-sky-200',
  REFUNDED: 'bg-sky-900/60 text-sky-200',
}

export function StatusBadge({ status }: { status: BookingStatus }) {
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STYLES[status]}`}>
      {status.replace('_', ' ').toLowerCase()}
    </span>
  )
}
