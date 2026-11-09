import { useSyncExternalStore } from 'react'
import { CITIES } from './format'

const KEY = 'cinebot.city'
const listeners = new Set<() => void>()

const read = () => localStorage.getItem(KEY) ?? CITIES[0]

/** Selected city, shared across pages and remembered between visits. */
export function useCity(): [string, (city: string) => void] {
  const city = useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    read,
  )
  const set = (value: string) => {
    localStorage.setItem(KEY, value)
    listeners.forEach((l) => l())
  }
  return [city, set]
}
