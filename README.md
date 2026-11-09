# CineBot

Movie ticket booking platform with a conversational (Gemini) booking assistant.
Spring Boot 3 · Java 21 · PostgreSQL · Redis · React + TypeScript + Tailwind.

- Specification: [`docs/00-master-prompt.md`](docs/00-master-prompt.md)
- System design & decisions: [`docs/01-design.md`](docs/01-design.md)
- Step 2 notes (catalog & TMDB sync): [`docs/02-movie-catalog.md`](docs/02-movie-catalog.md)
- Step 3 notes (theatres, screens, shows): [`docs/03-venues-and-shows.md`](docs/03-venues-and-shows.md)
- Step 4 notes (seat locking, bookings, concurrency): [`docs/04-seat-locking-and-bookings.md`](docs/04-seat-locking-and-bookings.md)
- Step 5 notes (payments, webhooks, refunds, recovery): [`docs/05-payments.md`](docs/05-payments.md)
- Step 6 notes (transactional outbox, notifications, analytics; no Kafka): [`docs/06-events-outbox.md`](docs/06-events-outbox.md)
- Steps 7–8 notes (chatbot structure, tools, safety, Gemini checklist): [`docs/07-chatbot.md`](docs/07-chatbot.md)
- Frontend notes (screens, auth handling, decisions): [`docs/08-frontend.md`](docs/08-frontend.md)

## Progress

| Step | Scope | Status |
|------|-------|--------|
| 0 | Requirements, ER model, state machine, locking strategy, API contracts | ✅ |
| 1 | Project setup, auth (JWT + rotating refresh tokens), error handling | ✅ |
| 2 | Movie catalog, genres, TMDB sync, typo-tolerant search | ✅ |
| 3 | Theatres, screens, seat layouts, shows, per-show inventory | ✅ |
| 4 | Seat locking (Redis + PostgreSQL), bookings, concurrency | ✅ |
| 5 | Payments (simulated gateway), webhooks, idempotency, confirmation, refunds, tickets | ✅ |
| 6 | Transactional outbox, notification + analytics handlers (no Kafka: ADR-10) | ✅ |
| 7–8 | Chatbot module: orchestrator, 7 tools, safety rules, stub model (Gemini later) | ✅ structure |
| 9 | React frontend: all 11 screens | ✅ |
| 10+ | Load tests (k6), CI/CD, deployment | ⏭ next |

## Run locally

Requirements: JDK 21, Docker.

```bash
cd infra && docker compose up -d          # Postgres on localhost:5433, Redis on localhost:6381
cd ../backend && ./mvnw spring-boot:run   # API on http://localhost:8085 (dev profile)
```

```bash
cd frontend && npm install && npm run dev  # UI on http://localhost:5173 (proxies /api to :8085)
```

Swagger UI: http://localhost:8085/swagger-ui.html · Health: http://localhost:8085/actuator/health

Dev profile extras: 10 sample movies, 4 theatres (Chennai, Mumbai, Bengaluru) with seat layouts, shows for the
next 3 days (topped up on every start), and an admin account `admin@cinebot.dev` / `Admin@12345`.

```bash
curl -s "localhost:8085/api/v1/shows?city=Chennai&from=20:00"     # tonight after 8 PM
curl -s "localhost:8085/api/v1/shows/1/seats"                     # seat map
```

To import real movies, set `TMDB_READ_ACCESS_TOKEN` (or `TMDB_API_KEY`) and call
`POST /api/v1/admin/movies/sync` with an admin token.

Pay for a booking (simulated provider):
```bash
curl -s -X POST localhost:8085/api/v1/payments/orders -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"bookingId":1}'
curl -s -X POST localhost:8085/api/v1/payments/simulator/checkout -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"orderId":"order_...","outcome":"SUCCESS"}'      # or FAILURE; webhooks arrive ~2 s later (twice, in dev)
```

Chat (stub model, command-driven until Gemini is integrated):
```bash
curl -s -X POST localhost:8085/api/v1/chat/sessions -H "Authorization: Bearer $T"            # -> sessionId
curl -s -X POST localhost:8085/api/v1/chat/messages -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"sessionId":"...","message":"/movies interstellar"}'                                     # /shows /seats /lock /pay
```

## Tests

```bash
cd backend
./mvnw test     # unit tests (fast, no Docker)
./mvnw verify   # + integration tests with Testcontainers (needs Docker)
```

If `java -version` isn't 21: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

## Try the auth API

```bash
curl -s -X POST localhost:8085/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"Password123","fullName":"Me"}'
curl -s localhost:8085/api/v1/users/me -H "Authorization: Bearer <accessToken>"
curl -s -X POST localhost:8085/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken>"}'
```
