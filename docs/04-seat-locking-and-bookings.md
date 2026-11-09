# Step 4 — Seat Locking & Booking Engine

## Requirements
- Lock 1–10 seats of a show for 8 minutes; all or nothing. One active lock per user per show.
- Exactly one winner when many users want the same seat; no double ownership, no partial locks, no deadlocks.
- Expired locks free their seats (immediately for readers and new lockers; tidied by a sweeper).
- A lock becomes a PENDING booking (idempotent); unpaid bookings can be cancelled; locks on cancelled or started shows are refused.
- **Redis being down must not affect correctness.**

## Data model (V4)
```
seat_locks (id UUID, user_id, show_id, seat_count, status ACTIVE|RELEASED|EXPIRED|CONSUMED, expires_at, closed_at)
show_seats + lock_id → seat_locks, locked_until, booking_id → bookings
bookings   (booking_reference UK, user_id, show_id, seat_lock_id UK, status, total_amount_paise, expires_at, version)
booking_seats (booking_id, show_seat_id, seat_label, seat_type, price_paise, confirmed)
```
| Constraint | Guarantees |
|---|---|
| `UNIQUE(show_id, seat_id)` (V3) | one inventory row per seat per show, so one owner at a time |
| `CHECK (status='LOCKED') = (lock_id IS NOT NULL) …` / `(status='BOOKED') = (booking_id IS NOT NULL)` | status and ownership can never disagree |
| `UNIQUE(user_id, show_id) WHERE status='ACTIVE'` | anti-hoarding, race-safe |
| `UNIQUE(seat_lock_id)` on bookings | one lock → at most one booking (idempotent create) |
| `UNIQUE(show_seat_id) WHERE confirmed` on booking_seats | defense in depth for Step 5: a seat can be confirmed once |

## Lock acquisition
```
SeatLockService.lock                         (no transaction)
 ├─ Redis Lua: all keys free? set all (PX 8m) : return conflicting seats ──► 409 in ~1 ms, DB untouched
 │     Redis error → circuit breaker opens 30 s → BYPASS (fail open)
 └─ SeatLockWriter.acquire                   (one DB transaction)
      1. SELECT show FOR SHARE         → must be SCHEDULED and not started   (serialises with cancel's FOR UPDATE)
      2. requested seats belong to show?                                      → 422
      3. user's ACTIVE lock: expired → close it (+ flush); valid → 409 ACTIVE_LOCK_EXISTS
      4. INSERT seat_locks (partial unique index)
      5. SELECT seats ORDER BY id FOR UPDATE SKIP LOCKED                       (fail fast, deterministic order)
      6. UPDATE show_seats SET LOCKED … WHERE id IN (…) AND (AVAILABLE OR lock expired)   ← THE AUTHORITY
         updated ≠ requested → throw → whole transaction rolls back           → 409 + unavailableSeatIds
    on any failure: Redis compare-and-delete of our keys
```

## Why each piece exists
| Piece | Role | What breaks without it (verified by mutation) |
|---|---|---|
| Conditional `UPDATE` predicate | Single source of truth | 20 users, Redis down: **3 winners for one seat** |
| `FOR SHARE` on the show row | Cancel and lock serialise | **cancelled show left with LOCKED seats** (round 1 of 15) |
| Redis gate | Turns losers away before the DB; fewer row locks | Still correct, just more DB contention |
| `SKIP LOCKED` + sorted row lock | Fail fast; lock order independent of the query plan | Still correct (tests pass); losers queue behind the winner instead of failing at once |
| Lazy expiry (`locked_until <= now` = free) | Expired seats are bookable before the sweeper runs | Seats stuck until the next sweep |
| Sweeper (`FOR UPDATE SKIP LOCKED`, batches) | Closes locks, expires bookings; safe on N instances | Data untidy, bookings stay PENDING |

## Redis failure modes
| Situation | Outcome |
|---|---|
| Redis down / slow | 250 ms timeout → breaker open 30 s → DB-only path. Readiness stays UP (Redis is excluded from the readiness group). |
| Redis key missing but DB lock valid | DB rejects (tested) |
| Redis key left after a crash | False "unavailable" for at most the TTL; never a double booking |
| Redis Cluster | `{showId}` hash tag keeps a show's keys in one slot, as multi-key Lua requires |

## Booking
- `POST /bookings {lockId}`: locks the seat_lock row, returns the existing booking if any (200), else checks ACTIVE + unexpired + all seats still held → PENDING (201). Prices and labels are snapshotted into `booking_seats`.
- **Lock order everywhere: `seat_locks` row → `bookings` row** (create, cancel, release, sweeper). One global order = no deadlocks between these paths.
- `cancel` reads only the lock id through a projection before locking, so no stale booking entity is cached and returned by the later `FOR UPDATE`.
- Another user's booking or lock → 404 (not 403).
- State machine (`BookingStatus`): every transition goes through `Booking.transitionTo`; the full 8×8 table is unit-tested.

## Bugs found by tests while building this
- **Hibernate flush ordering**: replacing an expired lock marked the old row EXPIRED (UPDATE) and saved a new ACTIVE row (INSERT). Hibernate flushes INSERTs before UPDATEs, so the unique index saw two ACTIVE locks. Fixed with an explicit flush.
- Adding the ownership CHECKs made earlier tests that set `BOOKED` directly fail. The constraint was right; the tests were updated.

## Not in this step
- Payment, confirmation (`LOCKED → BOOKED`, lock `CONSUMED`), refunds: Step 5. The sweeper has a marked hook so it won't expire bookings with a captured payment.
- Rate limiting and catalog caching with Redis: later polish step.
- k6 load tests (100 concurrent users, 1,000 requests): Week 9. The thread-level races here are the correctness proof.

## API
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/seat-locks` | `{showId, showSeatIds[]}` → 201 lock; 409 `SEATS_UNAVAILABLE` (+`unavailableSeatIds`), `ACTIVE_LOCK_EXISTS` (+`lockId`); 422 `SHOW_NOT_BOOKABLE`, `INVALID_SEAT_SELECTION` |
| DELETE | `/api/v1/seat-locks/{id}` | 204, idempotent; 409 `LOCK_IN_USE` when an open booking uses it |
| POST | `/api/v1/bookings` | `{lockId}` → 201 / 200 (repeat); 409 `LOCK_EXPIRED`, `LOCK_NOT_ACTIVE` |
| GET | `/api/v1/bookings/{id}` · `/api/v1/bookings/my?status=&page=&size=` | owner (or admin) |
| POST | `/api/v1/bookings/{id}/cancel` | PENDING/PAYMENT_PENDING → CANCELLED, seats freed; idempotent |

## Tests
`BookingStatusTest` (all 64 transitions) · `BookingReferenceGeneratorTest` · `RedisSeatLockGateIT` (Lua: all-or-nothing, conflicts, compare-and-delete, TTL, show isolation) · `SeatLockingIT` (HTTP flow, Redis/DB agreement, release, ownership, anti-hoarding, lazy expiry + takeover, sweeper, bookability, validation, CHECK constraints) · `SeatLockConcurrencyIT` (**20 users one seat**, **30 overlapping multi-seat**, 50 users different seats, **cancel-vs-lock ×15**, double click; global invariants incl. no orphan Redis keys) · `BookingFlowIT` (create, idempotent + concurrent create, expiry/takeover, 404 privacy, admin view, paging, cancel, sweeper expiry, concurrent sweepers, confirmed-once index) · `RedisOutageIT` (no Redis: **20-user race**, overlapping multi-seat, full flow, readiness UP).
