import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useCity } from '../lib/city'
import { CITIES } from '../lib/format'

const PinIcon = ({ className = '' }: { className?: string }) => (
  <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" className={className}>
    <path
      fillRule="evenodd"
      d="M9.69 18.93l.03.02a.75.75 0 00.56 0l.03-.02.08-.04a15 15 0 001.25-.8 17.4 17.4 0 002.86-2.6C16.06 13.75 17.5 11.4 17.5 8.5a7.5 7.5 0 00-15 0c0 2.9 1.44 5.25 3 6.99a17.4 17.4 0 004.11 3.4l.08.04zM10 11.25a2.75 2.75 0 100-5.5 2.75 2.75 0 000 5.5z"
      clipRule="evenodd"
    />
  </svg>
)

/** City switcher in the header: a styled listbox (keyboard + screen-reader friendly) instead of a native select. */
export function CityPicker() {
  const [city, setCity] = useCity()
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const id = useId()

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    list.current?.focus()
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const openList = () => {
    setActive(Math.max(0, CITIES.indexOf(city as (typeof CITIES)[number])))
    setOpen(true)
  }
  const close = () => {
    setOpen(false)
    button.current?.focus()
  }
  const choose = (value: string) => {
    setCity(value)
    close()
  }

  const onButtonKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      openList()
    }
  }
  const onListKey = (e: KeyboardEvent) => {
    const last = CITIES.length - 1
    if (e.key === 'ArrowDown') setActive((i) => (i === last ? 0 : i + 1))
    else if (e.key === 'ArrowUp') setActive((i) => (i === 0 ? last : i - 1))
    else if (e.key === 'Home') setActive(0)
    else if (e.key === 'End') setActive(last)
    else if (e.key === 'Enter' || e.key === ' ') choose(CITIES[active] ?? city)
    else if (e.key === 'Escape' || e.key === 'Tab') return close()
    else return
    e.preventDefault()
  }

  return (
    <div ref={root} className="relative">
      <button
        ref={button}
        type="button"
        aria-label={`City: ${city}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={`${id}-list`}
        onClick={() => (open ? close() : openList())}
        onKeyDown={onButtonKey}
        className={`group inline-flex items-center gap-1.5 rounded-full border py-1.5 pl-2.5 pr-2 text-sm font-medium transition
          focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500/60
          ${open
            ? 'border-rose-500/60 bg-slate-800 text-white'
            : 'border-slate-700 bg-slate-900 text-slate-200 hover:border-slate-600 hover:bg-slate-800 hover:text-white'}`}
      >
        <PinIcon className="h-4 w-4 text-rose-500" />
        <span>{city}</span>
        <svg
          viewBox="0 0 20 20"
          fill="currentColor"
          aria-hidden="true"
          className={`h-4 w-4 text-slate-400 transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
        >
          <path
            fillRule="evenodd"
            d="M5.23 7.21a.75.75 0 011.06.02L10 11.17l3.71-3.94a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"
            clipRule="evenodd"
          />
        </svg>
      </button>

      {open && (
        <ul
          ref={list}
          id={`${id}-list`}
          role="listbox"
          tabIndex={-1}
          aria-label="Choose a city"
          aria-activedescendant={`${id}-opt-${active}`}
          onKeyDown={onListKey}
          className="absolute left-0 z-20 mt-2 w-52 origin-top-left animate-chat-in overflow-hidden rounded-xl border
            border-slate-700/80 bg-slate-900/95 p-1.5 shadow-2xl shadow-black/50 ring-1 ring-white/5 backdrop-blur
            focus:outline-none"
        >
          <li role="presentation" className="px-2.5 pb-1.5 pt-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            Select city
          </li>
          {CITIES.map((c, i) => {
            const selected = c === city
            return (
              <li
                key={c}
                id={`${id}-opt-${i}`}
                role="option"
                aria-selected={selected}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(c)}
                className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm transition-colors
                  ${i === active ? 'bg-slate-800 text-white' : 'text-slate-300'}`}
              >
                <PinIcon className={`h-4 w-4 shrink-0 ${selected ? 'text-rose-500' : 'text-slate-600'}`} />
                <span className={`flex-1 ${selected ? 'font-semibold text-white' : ''}`}>{c}</span>
                {selected && (
                  <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" className="h-4 w-4 text-rose-500">
                    <path
                      fillRule="evenodd"
                      d="M16.7 5.3a1 1 0 010 1.4l-8 8a1 1 0 01-1.4 0l-4-4a1 1 0 111.4-1.4L8 12.58l7.3-7.3a1 1 0 011.4 0z"
                      clipRule="evenodd"
                    />
                  </svg>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
