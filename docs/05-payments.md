# Step 5 — Payments, Webhooks, Confirmation, Refunds

Provider: **simulated** (decision 2026-11-03). It behaves like a real one: separate durable state (`sim_*` tables,
own transactions), hosted checkout, HMAC-signed callbacks and webhooks, async delivery with retries and optional
duplicates, delayed refund settlement. Swapping in Razorpay = one more `PaymentGateway` implementation.

## Flow
```
POST /payments/orders {bookingId}      tx1 lock+validate │ provider.createOrder (no tx) │ tx2 insert payment, booking → PAYMENT_PENDING
   ↓ checkoutUrl
POST /payments/simulator/checkout      provider records CAPTURED/FAILED, returns {orderId, paymentId, signature}
   ↓                                    └─ webhook payment.captured / payment.failed (async, signed, retried)
POST /payments/verify                  check HMAC(orderId|paymentId) → ask provider "really captured? how much?" → applyCapture
POST /payments/webhook                 check HMAC(raw body) → [tx: insert event id (PK) + apply] → 200
Reconciler (every 1 min)               CREATED orders > 2 min old & REFUND_PENDING payments → ask provider → apply
```
All three entry points call the **same idempotent** `PaymentSettlementService` methods.

## applyCapture: one transaction
```
lock seat_lock → booking → payment            (global lock order, all paths)
same paymentId already recorded?  → no-op     (duplicate)
amount ≠ order amount / second capture → reject before any write
payment → CAPTURED
booking:
  PAYMENT_PENDING and lock still owns ALL seats (ownership, not clock) → seats BOOKED, booking CONFIRMED, lock CONSUMED
  PAYMENT_PENDING but a seat was taken over after expiry               → release rest, booking + payment REFUND_PENDING
  EXPIRED / PAYMENT_FAILED / CANCELLED (late capture)                  → booking + payment REFUND_PENDING
after commit: start refund (if owed); clear Redis keys
```
**Why one transaction?** If the process dies half-way, *nothing* is committed, so our DB never contains
"captured but unconfirmed". The fact is not lost: the provider holds it durably, and the webhook retry or the
reconciler re-applies it. Verified by fault injection (`cinebot.payment.faults`), both in tests and live.

## Failure scenarios
| Scenario | Handling | Test |
|---|---|---|
| Payment succeeds, backend crashes before confirming | tx rolls back → webhook retry / reconciler confirms | `crashAfterCaptureIsRecoveredByReconciliation` (+ live demo: recovered by webhook retry) |
| Browser closed, never calls verify | webhook confirms | `webhookAloneConfirms…` |
| Webhook lost too | reconciler confirms | `lostWebhookAndClosedBrowser…` |
| Same webhook delivered 5× concurrently | event-id PK → 1 PROCESSED, 4 DUPLICATE | `duplicateWebhooksDeliveredConcurrently…` |
| verify and webhook race | row locks + idempotency → confirmed once | `verifyAndWebhookRacing…` |
| Pay 30 s after lock TTL, seats untouched | grace: sweeper waits 2 min for PAYMENT_PENDING; confirm checks ownership → CONFIRMED | `paymentJustAfterExpiry…` |
| Pay after another user took a seat | refund; other user keeps the seat | `paymentAfterSeatTakeoverIsRefunded` |
| Pay after sweeper expired the booking / after user cancelled / after a failed attempt | refund | 3 tests |
| Refund call lost, refund webhook lost | reconciler starts / completes it; idempotency key = no double refund | `refundIsStartedAndCompleted…` |
| Forged / tampered / unsigned webhook | 400 | `webhookSecurityAndValidation` |
| Wrong amount, unknown order | acknowledged as REJECTED and recorded, so the provider stops retrying; nothing applied | `businessRejections…` |
| Order abandoned | reconciler marks payment EXPIRED; sweeper expires booking | `reconcilerLeavesFreshOrdersAlone…` |

## Webhook transaction rules
- Signature is over the **raw body** (the controller takes `String`, not a parsed object).
- Insert-event + apply share one transaction: if applying fails, the insert rolls back too, so the provider's retry is processed instead of being treated as a duplicate.
- Business rejection → separate tx records `outcome = REJECTED: CODE` → 200. Infrastructure failure → 5xx → provider retries.

## Refunds: persist intent, then act
`payment → REFUND_PENDING` is committed with the business change; the provider refund call runs **after commit**
(never inside a tx). Its result is stored in a `REQUIRES_NEW` transaction: inside `afterCommit` the finished
transaction's resources are still bound, and a `REQUIRED` template silently joins it, so the write is lost.
Verified: with `REQUIRED`, the refund id was never saved. Lost calls are retried by the reconciler with the
same idempotency key.

Cancelling a CONFIRMED booking (until 2 h before the show): seats `BOOKED → AVAILABLE`, `booking_seats.confirmed = false`
(so the "confirmed once" index allows resale), refund requested. Booking doesn't depend on payment: it calls the
`RefundRequester` port that the payment module implements (same pattern as `NowShowingQuery`).

## Payment states
`CREATED → CAPTURED | FAILED | EXPIRED`, `FAILED/EXPIRED → CAPTURED` (late money is recorded, then refunded),
`CAPTURED → REFUND_PENDING → REFUNDED`. One order per booking (`UNIQUE(booking_id)`).

A failed payment is terminal for the booking (`PAYMENT_FAILED`, seats released at once); to retry, lock seats again.

## Ticket
`CBT1.<reference>.<bookingId>.<128-bit HMAC>` in `BookingResponse.ticket.qrPayload` while CONFIRMED; the client renders
it as a QR code. `POST /admin/tickets/verify` checks the signature *and* that the booking is still CONFIRMED.

## Config
| Property | Default | Notes |
|---|---|---|
| `cinebot.payment.simulator.key-secret` / `webhook-secret` | env, required | dev/test have values |
| `…simulator.webhook-delivery` | HTTP | NONE in tests (tests deliver queued webhooks) |
| `…simulator.duplicate-webhooks` | false (dev: true) | |
| `cinebot.payment.reconciler.interval` / `stuck-after` | 1 min / 2 min | |
| `cinebot.booking.payment-grace` | 2 min | |
| `cinebot.booking.cancellation-cutoff` | 2 h | |
| `cinebot.payment.faults.enabled` | false (dev/test: true) | `POST /admin/payment-faults {"crashAfterCapture":true}` |
| `cinebot.ticket.secret` | env, required | |

Kafka events (`BookingConfirmed`, `PaymentSucceeded`, …) via a transactional outbox come in Step 6.
