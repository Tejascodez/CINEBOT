# Step 3 — Theatres, Screens, Seats, Shows

## Requirements
- Admin: create/update theatres, add screens, define seat layouts, schedule and cancel shows with per-seat-type prices.
- Public: theatres by city, theatre detail with screens, show search (movie / theatre / city, date, time window), show detail, seat map, and `GET /movies?city=` ("now showing in city").
- Times are stored in UTC; "today", "tonight" and "after 8 PM" are evaluated in **Asia/Kolkata**.

## Data model (V3)
```
theatres 1─N screens 1─N seats            (physical; changed only by an admin redesign)
movies   1─N shows   N─1 screens
shows    1─N show_seats N─1 seats          (per-show inventory: what Step 4 locks and books)
```

| Constraint / index | Purpose |
|---|---|
| `EXCLUDE USING gist (screen_id WITH =, tstzrange(start_time, end_time,'[)') WITH &&) WHERE status='SCHEDULED'` | **No two scheduled shows overlap on a screen.** Race-safe, unlike check-then-insert. Half-open range allows back-to-back shows; cancelled shows release their slot. Needs `btree_gist`. |
| `UNIQUE(show_id, seat_id)` on `show_seats` | One inventory row per seat per show: the foundation of double-booking prevention |
| `CHECK price_paise > 0`, `NOT NULL` | A missing price can never become a free seat |
| `UNIQUE(lower(city), lower(name))` on theatres | No duplicate theatres per city; also the city lookup index |
| `UNIQUE(theatre_id, name)` screens · `UNIQUE(screen_id, row_label, seat_number)` seats | No duplicate labels |
| `show_seats.seat_id` FK + index | Changing a layout after shows exist fails at the DB → 409 |
| `(show_id, status)` | Seat maps and available-seat counts |

`end_time` = start + runtime + 20 min turnaround (ads, cleaning): the interval the screen is occupied.
Lock and booking columns on `show_seats` (`lock_id`, `locked_until`, `booking_id`) come in Step 4, with the tables they reference.

## Module dependencies (acyclic)
```
show ──► movie, screen, theatre, seat
theatre ──► screen          seat ──► screen          movie ──► (nothing)
show ──implements──► movie.NowShowingQuery
```
- `Screen` holds `theatreId` and `ShowSeat` holds `showId` as plain ids instead of `@ManyToOne`, so the reverse dependencies never appear.
- `GET /movies?city=` needs show data, but movie must not depend on show (show already depends on movie). The movie module defines the `NowShowingQuery` interface and the show module implements it: **dependency inversion**.

## Key decisions
| Decision | Why |
|---|---|
| Inventory created with one `INSERT … SELECT` | 1 statement instead of N entity inserts (92 seats → 1 round trip). |
| Same transaction as the show (`Propagation.MANDATORY`) | A show is never visible without its full inventory; MANDATORY fails fast if someone calls it outside a transaction. |
| Overlap mapped by constraint name (`Constraints.isViolation`) | Different constraints → different API errors, with no fragile message parsing in services. |
| Read model in SQL (`ShowQueryRepository`) | Show listing = 5-table join + aggregates (available seats, min price) in one `GROUP BY`; JPA entities stay the write model. |
| Show search bounded to one day, ≤200 rows, must be scoped | No pagination needed; no accidental nationwide scans. |
| Times returned with `+05:30` offset | Unambiguous and directly readable by users and the chatbot. |
| Optional filters via Specification, not `(:p IS NULL OR …)` | On PostgreSQL an untyped null parameter is sent as `bytea` (`lower(bytea)` error). Caught by a test. |
| Cancel = lock show row, refuse if seats LOCKED/BOOKED, block the rest | Cancelling sold shows needs refunds (Step 5). Step 4 must also make seat locking reject non-SCHEDULED shows. |
| Dev seeder through the real services | Seed data obeys production rules; on restart the overlap constraint makes it idempotent. |

## API
| Method | Path | Auth |
|---|---|---|
| GET | `/api/v1/theatres?city=&brand=&page=&size=` | public (city required) |
| GET | `/api/v1/theatres/{id}` | public (inactive → 404) |
| GET | `/api/v1/shows?movieId=&theatreId=&city=&date=YYYY-MM-DD&from=HH:mm&to=HH:mm` | public (≥1 of movieId/theatreId/city) |
| GET | `/api/v1/shows/{id}` · `/api/v1/shows/{id}/seats` | public |
| GET | `/api/v1/movies?city=` | public |
| POST/PUT | `/api/v1/admin/theatres`, `/admin/theatres/{id}` | ADMIN |
| POST | `/api/v1/admin/theatres/{id}/screens` | ADMIN |
| GET/PUT | `/api/v1/admin/screens/{id}`, `/admin/screens/{id}/layout` | ADMIN |
| POST | `/api/v1/admin/shows`, `/admin/shows/{id}/cancel` | ADMIN |

## Tests
- `ShowSearchWindowTest` (unit): IST day → UTC window, "after 8 PM", started shows clipped, past windows skip the query.
- `VenueAdminIT`: create flow, case-insensitive duplicates, layout validation, inactive theatres hidden, city required, admin-only.
- `ShowSchedulingIT`: priced inventory, end time, overlap/back-to-back/other screen/cancel frees slot, **8-thread overlapping race → exactly 1 show** (verified to produce 8 without the constraint), pricing and preconditions, frozen layout, cancel rules.
- `ShowDiscoveryIT`: city/movie/date/time search, **IST midnight boundary**, cancelled/inactive hidden, availability counts, seat map order, `movies?city=`, validation, 404s.
