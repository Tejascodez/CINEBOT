# Steps 7–8 — Chatbot Module (structure; Gemini later)

**Decision (2026-11-04):** build the complete chatbot structure now; integrate Gemini later. Everything except
the model is real and tested: endpoints, conversation storage, context, tools wired to the real services,
validation, safety rules. A command-driven `StubLlmClient` stands in for the model.

## Architecture
```
POST /api/v1/chat/messages
  ChatService (orchestrator, no DB tx around model calls)
    save USER message
    loop ≤ maxToolRounds (5):
      LlmClient.complete(systemPrompt, last 30 turns, tool declarations)
        text        → save ASSISTANT (+ cards) → return {reply, cards}
        tool calls  → for each: save call → ToolRegistry.execute → save TOOL result → add cards
    limit hit → "Sorry, I couldn't complete that…"
```
| Piece | Role |
|---|---|
| `LlmClient` | model port. Request = system prompt + turns + `ToolSpec`s; reply = text or `ToolCall`s (Gemini function-calling shape) |
| `ChatTurn` (sealed) | `User`, `AssistantText`, `ToolCallTurn`, `ToolResultTurn` |
| `ChatTool<A>` | name, description, JSON-schema `parameters()`, typed + Bean-Validated arguments, `execute` |
| `ToolRegistry` | unknown tool / undeclared or invalid args / `ApiException` → **error results** the model can explain |
| `ChatContext` | ids only (showId, lockId, bookingId) persisted on the session, so "lock A1,A2" or "pay" need no ids |
| `Card` | UI payloads (MOVIES, SHOWS, SEAT_MAP, BOOKING_SUMMARY, PAYMENT_CONFIRMATION_REQUIRED, TICKET), never sent to the model |
| `system-prompt.md` | the model's rules (only facts from tools, never claim payment, ₹ from paise, IST) |

## Tools
| Tool | Calls | Notes |
|---|---|---|
| `searchMovies(query?, genre?, language?, city?)` | `MovieService` | typo-tolerant search or filtered list |
| `searchTheatres(city, brand?)` | `TheatreService` | |
| `findShows(movieId?, theatreId?, city?, date?, after?, before?)` | `ShowService.search` | IST windows ("after 20:00") |
| `getAvailableSeats(showId?)` | `ShowService.seatMap` | model gets free labels grouped by type and price; UI gets the full map |
| `lockSeats(showId?, seats[])` | `SeatLockService` + `BookingService.create` | the booking engine decides; taken seats are reported by label |
| `getBookingSummary(bookingId?)` | `BookingService.get` | the real status; TICKET card when CONFIRMED |
| `initiatePayment(bookingId?)` | `BookingService.get` only | **cannot pay**: returns a Confirm & Pay card; the human's click calls `POST /payments/orders` |

## Security requirements → enforcement
| Requirement | How it is enforced | Test |
|---|---|---|
| AI never touches the DB | tools call services only; the model only sees tool results | (structure) |
| AI never decides availability | `lockSeats` goes through the booking engine; a model "believing" A1 is free changes nothing | `availabilityComesFromTheDatabaseNotTheModel` |
| AI never assumes payment succeeded | prompt rule + `initiatePayment` result says "nothing charged"; status only via `getBookingSummary` | `modelCannotPayEvenWhenItTriesEverything` |
| Every tool call is validated | strict JSON → typed args (unknown fields rejected) → Bean Validation → service rules | `invalidArgumentsAreRejected…` (5 kinds) |
| Payment needs explicit user authorisation | no payment code reachable from the chatbot: **ArchUnit** `chatbotCannotTouchPayments` | `ArchitectureTest`, spec scenario 8 |
| No other user's data | tools run as the signed-in user; ownership → 404 | `modelCannotReadOrPayForAnotherUsersBooking` |
| No unnecessary personal data to the LLM | curated tool DTOs (no email, phone, lock/payment ids) | `personalDataNeverReachesTheModel` |
| Runaway loops | `maxToolRounds` | `runawayToolLoopIsCutOff` |

## Stub commands (until Gemini)
`/movies [title]` · `/theatres <city>` · `/shows <movieId> <city> [YYYY-MM-DD] [HH:mm]` · `/seats [showId]` ·
`/lock [showId] A1,A2` · `/summary` · `/pay`. Anything else returns help. The stub echoes tool summaries verbatim,
so model-facing hints ("Do not say the booking is paid") are visible; a real model phrases its own reply.

## Adding Gemini later (checklist)
1. `GeminiLlmClient implements LlmClient`, `@ConditionalOnProperty(cinebot.chat.llm.provider=gemini)`; API key from env (`GEMINI_API_KEY`), never committed.
2. Map: `systemPrompt` → `systemInstruction`; `ToolSpec` → `tools[].functionDeclarations` (`parameters` is already JSON schema);
   `User`/`AssistantText` → `contents` with role user/model; `ToolCallTurn` → model `functionCall`; `ToolResultTurn` → user `functionResponse`.
3. Response: `functionCall` parts → `ToolCall`s; text parts → text.
4. RestClient with timeouts (`spring.http.client.*`), retries on 429/5xx, a model-name property, a token budget.
5. Rate-limit `/chat/messages` per user (Redis) before enabling, since every message costs money.
6. Keep `ChatSafetyIT` green: it plays a hostile model and must pass with any provider.
