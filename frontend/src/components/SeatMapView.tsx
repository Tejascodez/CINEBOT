import type { SeatMap, SeatView } from '../api/types'
import { money } from '../lib/format'

const TYPE_STYLE: Record<SeatView['type'], string> = {
  REGULAR: 'border-slate-500 hover:bg-slate-700',
  PREMIUM: 'border-violet-400 hover:bg-violet-900/60',
  RECLINER: 'border-amber-400 hover:bg-amber-900/60 rounded-t-xl',
}

interface Props {
  map: SeatMap
  selected: number[]
  onToggle?: (seat: SeatView) => void
  highlight?: number[] // e.g. seats the server just reported as taken
  compact?: boolean
}

/** Row A is nearest the screen. Only AVAILABLE seats can be toggled; the server re-checks everything anyway. */
export function SeatMapView({ map, selected, onToggle, highlight = [], compact = false }: Props) {
  const size = compact ? 'h-5 w-5 text-[9px]' : 'h-8 w-8 text-[11px]'
  const prices = new Map<string, number>()
  map.rows.forEach((r) => r.seats.forEach((s) => prices.set(s.type, s.pricePaise)))

  return (
    <div className="overflow-x-auto">
      <div className="mx-auto w-max">
        <div className="mx-auto mb-6 h-2 w-3/4 rounded-full bg-gradient-to-r from-transparent via-sky-300/70 to-transparent" />
        <p className="-mt-4 mb-4 text-center text-[10px] uppercase tracking-[0.3em] text-slate-500">screen</p>
        {map.rows.map((row) => (
          <div key={row.label} className="mb-1.5 flex items-center gap-1.5">
            <span className="w-5 text-right text-xs text-slate-500">{row.label}</span>
            {row.seats.map((seat) => {
              const isSelected = selected.includes(seat.showSeatId)
              const free = seat.status === 'AVAILABLE'
              const state = isSelected
                ? 'bg-rose-600 border-rose-500 text-white'
                : !free
                  ? 'border-slate-800 bg-slate-800 text-slate-600 cursor-not-allowed'
                  : TYPE_STYLE[seat.type]
              return (
                <button
                  key={seat.showSeatId}
                  type="button"
                  disabled={!free || !onToggle}
                  onClick={() => onToggle?.(seat)}
                  aria-pressed={isSelected}
                  aria-label={`${seat.label} ${seat.type.toLowerCase()} ${money(seat.pricePaise)} ${free ? 'available' : seat.status.toLowerCase()}`}
                  title={`${seat.label} · ${money(seat.pricePaise)}`}
                  className={`${size} rounded-md border text-center font-medium transition ${state} ${
                    highlight.includes(seat.showSeatId) ? 'ring-2 ring-rose-400' : ''}`}
                >
                  {compact ? '' : seat.number}
                </button>
              )
            })}
          </div>
        ))}
        {!compact && (
          <div className="mt-5 flex flex-wrap justify-center gap-4 text-xs text-slate-400">
            {[...prices].map(([type, price]) => (
              <span key={type} className="flex items-center gap-1.5">
                <span className={`h-3 w-3 rounded border ${TYPE_STYLE[type as SeatView['type']]}`} />
                {type.toLowerCase()} {money(price)}
              </span>
            ))}
            <span className="flex items-center gap-1.5"><span className="h-3 w-3 rounded bg-rose-600" />selected</span>
            <span className="flex items-center gap-1.5"><span className="h-3 w-3 rounded bg-slate-800" />taken</span>
          </div>
        )}
      </div>
    </div>
  )
}
