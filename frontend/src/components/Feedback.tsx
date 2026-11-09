import { ApiError } from '../api/client'

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-3 p-8 text-slate-400">
      <span className="h-5 w-5 animate-spin rounded-full border-2 border-slate-600 border-t-rose-500" />
      {label}
    </div>
  )
}

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiError ? error.message : 'Something went wrong. Please try again.'
  const trace = error instanceof ApiError ? error.problem.traceId : undefined
  return (
    <div role="alert" className="rounded-lg border border-rose-900 bg-rose-950/60 px-4 py-3 text-sm text-rose-200">
      {message}
      {trace && <span className="ml-2 text-xs text-rose-400/70">ref {trace.slice(0, 8)}</span>}
    </div>
  )
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="rounded-xl border border-dashed border-slate-800 p-8 text-center text-slate-400">{children}</p>
}
