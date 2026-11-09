import { useEffect, useState } from 'react'

/** Live mm:ss until `until`; calls onExpire once when it reaches zero. */
export function Countdown({ until, onExpire }: { until: string; onExpire?: () => void }) {
  const target = new Date(until).getTime()
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  const left = Math.max(0, Math.floor((target - now) / 1000))
  useEffect(() => {
    if (left === 0) onExpire?.()
  }, [left, onExpire])
  const mm = String(Math.floor(left / 60)).padStart(2, '0')
  const ss = String(left % 60).padStart(2, '0')
  return (
    <span className={`font-mono tabular-nums ${left < 60 ? 'text-rose-400' : 'text-amber-300'}`} aria-live="polite">
      {mm}:{ss}
    </span>
  )
}
