# Step 6 — Domain Events: Transactional Outbox, No Kafka

**Decision (2026-11-04, ADR-10): no Kafka.** At this scale it adds infrastructure without a use case: one
deployable, a few consumers, modest volume, no replay requirement. The hard part (never losing or inventing an
event) is solved by the **transactional outbox**, which works the same with or without a broker.
Kafka becomes worth it with several independently deployed consumers, replay needs, or volume beyond one
database. Then the relay would publish to Kafka instead of calling handlers in-process.

## The problem it solves: dual writes
"Update the booking, then send an event" are two writes to two systems. A crash between them either loses the
event (customer never gets the ticket email) or announces a change that rolled back. Neither write order is
safe on its own.

## Design
```
business tx:  booking CONFIRMED  +  INSERT outbox_events(booking.confirmed)     ← one commit, or neither
relay (1 s):  lease batch: UPDATE … SET locked_until = now+30s WHERE id IN (… FOR UPDATE SKIP LOCKED) RETURNING *
              for each handler subscribed to the type:
                  tx { INSERT processed_events(handler, event) ON CONFLICT DO NOTHING; if inserted → handler.handle() }
              all ok → DONE · else attempts+1, available_at = now + 2^attempts s (≤10 min) · 8 failures → DEAD
```
| Property | How |
|---|---|
| Atomic with the state change | `OutboxWriter.append` is `MANDATORY`: it refuses to run outside the business transaction |
| No change without its event | `Booking.transitionTo` is package-private; services must use `BookingTransitions.move`, which also appends the event |
| Several relay instances | lease via `FOR UPDATE SKIP LOCKED`; no transaction stays open while handlers run |
| Worker dies mid-batch | lease expires after 30 s, another worker takes the events |
| Exactly-once *effect* | delivery is at-least-once; `processed_events(handler, event_id)` is written in the handler's own transaction |
| One handler fails | only it is retried; handlers that succeeded are skipped on redelivery |
| Poison event | DEAD after 8 attempts; `GET /admin/outbox/dead`, `POST /admin/outbox/{id}/retry` |
| Ordering | not guaranteed across a batch. Handlers are order-independent: counters are commutative, and messages carry the state they announce. (Kafka partitions keyed by booking id would give per-booking order; we don't need it.) |
| External side effects (email) | at-least-once: if the send succeeds and the commit then fails, the email is sent again. Acceptable for email; payments never go through this path. |

## Events (`BookingEvent`, aggregate `booking`)
`booking.created`, `booking.payment_started`, `booking.confirmed`, `booking.payment_failed`, `booking.expired`,
`booking.cancelled`, `booking.refund_requested`, `booking.refunded`. The payload is self-describing (reference,
user, show, from→to status, amount, seat labels).

## Handlers
| Handler | Subscribes to | Effect |
|---|---|---|
| `notification` | confirmed, payment_failed, expired, refund_requested, refunded | `notifications` row + `EmailSender` (logging for now). Confirmation includes the QR ticket payload. `GET /api/v1/notifications/my` |
| `sales-analytics` | confirmed, refund_requested (from CONFIRMED only) | `show_sales_stats` counters: +1 sale / −1 on refund. Late-capture refunds were never sales. `GET /api/v1/admin/stats/shows/{id}` |

## Tests
- `OutboxMechanicsIT`: rollback ⇒ no event; MANDATORY enforced; fan-out; retry with backoff without re-running succeeded handlers; DEAD after max attempts + admin replay; lease blocks other workers until expiry; **4 concurrent relays × 40 events ⇒ each handled exactly once**; redelivery after "crash before DONE" is harmless; backoff cap.
- `BookingEventsIT`: lifecycle publishes exactly one event per real change (duplicates and reconciler runs publish nothing); a failed lock or rejected transition publishes nothing; ticket email; analytics up and down on refund; a late-capture refund is not a sale; reprocessing every event changes nothing.

## Not done
- No cleanup of old DONE events yet (would be a nightly `DELETE … WHERE status = 'DONE' AND processed_at < now() - 7 days`).
- Real email provider: implement `EmailSender`.
