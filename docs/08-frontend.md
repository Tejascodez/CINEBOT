# Frontend — React + TypeScript + Tailwind

`frontend/` · Vite 8 · React 19 · TypeScript 5.9 (strict, `noUncheckedIndexedAccess`) · Tailwind 4 ·
React Router 7 · TanStack Query 5 · qrcode.react · Vitest + Testing Library.

Dev server: **http://localhost:5173**. `/api` is proxied to the backend
on 8085, so the browser talks to one origin and no CORS is involved in development.

## Screens (spec §13)
| # | Screen | Route | Notes |
|---|---|---|---|
| 1 | Login / registration | `/login`, `/register` | field errors from the backend's `errors[]`; returns to the page you came from |
| 2 | Discovery | `/` | city selector (remembered), genre chips, typo-tolerant search, "now showing in city" |
| 3 | Movie details | `/movies/:id` | overview, runtime, rating |
| 4 | Theatre + showtime | same page | next 3 IST days, grouped by theatre, seats left, from-price; refreshes every 30 s |
| 5 | Seat selection | `/shows/:id/seats` | screen-side row A, type colours and price legend, max 10, refresh every 10 s; taken seats from a 409 are highlighted and removed from the selection; "already holding seats" offers release |
| 6 | Booking summary | `/bookings/:id` | live hold countdown, Pay, Cancel |
| 7 | Payment | `/checkout/:orderId` | the simulated provider's hosted checkout (Pay / Decline) → `/payments/verify` |
| 8 | Confirmation + QR | `/bookings/:id` | polls every 2 s while PAYMENT_PENDING (webhook path); QR when CONFIRMED; cancel & refund; explains failed/expired/refund states |
| 9 | Booking history | `/bookings` | plus the "inbox" of emails from the notification handler |
| 10 | Chat bot | floating bubble, bottom-right of every page | opens a chat panel; the conversation survives navigation (it lives in the layout); Esc closes; signed-out users get a sign-in prompt; cards are clickable (movie → shows → seat map → hold); **Confirm & Pay** is a real button that calls the payment API |
| 11 | Admin | `/admin` (ADMIN) | show sales, outbox + dead-letter retry, ticket gate check, TMDB sync, crash-after-capture toggle |

## Key decisions
| Decision | Why |
|---|---|
| Access token in memory, refresh token in `localStorage` | Survives reloads without keeping the bearer token in storage. Trade-off: `localStorage` is readable by XSS; an httpOnly refresh cookie (backend change) would be stronger. Rotation + reuse detection limit a stolen token. |
| **Single-flight refresh** | Several requests failing with 401 at once must share one `/auth/refresh`. A second refresh with the same token looks like theft to the backend and revokes the whole session. Unit-tested with 3 concurrent 401s → 1 refresh. |
| `ApiError` carries the problem+json | UI switches on `code` (`SEATS_UNAVAILABLE`, `ACTIVE_LOCK_EXISTS`…) and uses extra fields (`unavailableSeatIds`, `lockId`), never message text |
| TanStack Query | caching, polling (`refetchInterval`), loading/error states; no retries on 4xx |
| Server stays authoritative | the seat map only *suggests*; every hold is re-validated by the booking engine, and the UI reacts to its answer |
| IST everywhere | `Intl` with `Asia/Kolkata`, independent of the browser's zone (unit-tested near midnight) |

## Run / test
```bash
cd frontend
npm install
npm run dev        # http://localhost:5173  (backend must run on 8085)
npm test           # Vitest
npm run build      # typecheck + production bundle in dist/
```

## Not done yet
- Admin forms for theatres / screens / layouts / shows (use Swagger UI for now).
- End-to-end browser tests (Playwright) and visual polish/accessibility audit.
- Docker image for the frontend (with the deployment step).
