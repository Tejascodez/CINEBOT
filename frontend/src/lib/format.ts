// Display helpers. The backend sends money as integer paise and times with an explicit +05:30 offset.

const ZONE = 'Asia/Kolkata'
const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })

export const money = (paise: number | null | undefined) => (paise == null ? '—' : rupees.format(paise / 100))

export const time = (iso: string) =>
  new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: ZONE }).format(new Date(iso))

export const dateTime = (iso: string) =>
  new Intl.DateTimeFormat('en-IN', {
    weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true, timeZone: ZONE,
  }).format(new Date(iso))

/** YYYY-MM-DD of "today + offset days" in India, whatever the browser's own time zone is. */
export function istDate(offsetDays = 0, now = new Date()): string {
  const shifted = new Date(now.getTime() + offsetDays * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(shifted)
}

export const dayLabel = (isoDate: string) =>
  new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })
    .format(new Date(`${isoDate}T00:00:00Z`))

export const runtime = (minutes: number | null) =>
  minutes == null ? '' : `${Math.floor(minutes / 60)}h ${minutes % 60}m`

export const LANGUAGES: Record<string, string> = {
  en: 'English', hi: 'Hindi', ta: 'Tamil', te: 'Telugu', ml: 'Malayalam', kn: 'Kannada', ko: 'Korean', ja: 'Japanese',
}
export const languageName = (code: string | null) => (code ? (LANGUAGES[code] ?? code.toUpperCase()) : '')

export const CITIES = ['Chennai', 'Mumbai', 'Bengaluru'] as const
