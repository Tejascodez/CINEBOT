# Step 9 — AI booking agent (Spring AI + Gemini)

Replaces the Step 7 command-driven chatbot loop with a real LLM agent. The agent is an **interface to the
existing backend**: it decides *which* backend operation to run and *with which arguments*; the backend still
decides *whether* it is allowed (auth, validation, transactions, seat locking, pricing).

Decisions (agreed):
- Spring AI **1.1.8** (the line for Spring Boot 3.5; 2.x needs Boot 4). Provider: **Gemini** via
  `spring-ai-starter-model-google-genai` (API key). The agent code depends only on `ChatClient` / `ChatModel`,
  so switching provider = swapping the starter + config.
- Endpoint **`POST /api/v1/agent/chat`** (replaces `/api/v1/chat/*`).
- **Hybrid confirmation**: seat holds and cancellations are confirmed in chat through a server-enforced
  prepare → confirm step; **payment stays a button** the model cannot press.

## 1. Architecture

```
React chat widget
   │  POST /api/v1/agent/chat {conversationId, message}          (JWT = the user)
   ▼
AgentController ──► MovieBookingAgentService  (the tool-execution loop, our code)
                        │   ▲
              prompt +  │   │ AssistantMessage: text, or tool calls {name, JSON args}
         tool defs      ▼   │
                     ChatClient ──► ChatModel (Gemini)
                        │
                        │ tool calls ─► ToolCallingManager ─► GuardedToolCallback ─► @Tool method
                        │                                                         │
                        │                                    same services as the REST controllers:
                        │                       MovieService, TheatreService, ShowService, SeatLockService,
                        │                                           BookingService
                        ▼
              PersistentChatMemory (chatbot_messages)   ConversationState (chatbot_sessions.context)
```

| Package (`com.cinebot.agent`) | Purpose |
|---|---|
| `controller/AgentController` | `POST /api/v1/agent/chat`, `GET /api/v1/agent/conversations/{id}` |
| `service/MovieBookingAgentService` | the tool loop: model → tools → model, bounded, persisted |
| `tools/*Tools` | `@Tool` methods; each wraps an existing service method (= an existing API) |
| `tools/GuardedToolCallback` | turns any tool failure into a structured result; records `toolCalls` |
| `tools/ConversationState`, `PendingAction` | structured memory + server-side confirmation records |
| `memory/PersistentChatMemory` | Spring AI `ChatMemory` over the existing chat tables |
| `config/` | properties, tool registration, ChatClient |
| `dto/` | `AgentRequest`, `AgentResponse` |

**Why tools call services, not the REST API over HTTP.** A tool *is* an API client, but in-process: it calls the
exact service method the controller calls (same validation, same transaction, same ownership checks), acting
as the signed-in user. Going through HTTP would mean handing the user's JWT to the tool layer, adding latency
and a failure mode, for zero extra safety. No new backend endpoints are created; no business logic is
duplicated.

## 2. How the LLM decides which tool to call

The model never sees Java. For every request Spring AI sends it a list of **tool definitions** generated from
the `@Tool` methods:

```json
{ "name": "findShowtimes",
  "description": "Find upcoming showtimes (India time) for a movie and/or a theatre and/or a city, on one date ... Words like 'tomorrow' must be converted to an ISO date ...",
  "parameters": { "type": "object",
                  "properties": { "movieId": {"type": "integer", "description": "movieId from searchMovies"},
                                  "city": {"type": "string"}, "date": {"type": "string", "description": "YYYY-MM-DD"} ... },
                  "required": [] } }
```

Function calling is a trained capability: given the conversation, the system instructions and these
definitions, the model either answers in text or emits a structured **tool call**
`{name: "findShowtimes", args: {"movieId": 7, "city": "Chennai", "date": "2026-11-05"}}`. It picks the tool
whose *description* matches the user's intent and fills arguments from what the user said, earlier tool results
and the conversation state. That is why there is **no keyword matching** anywhere: "I want to watch
Interstellar", "is Interstellar on?" and "book Interstellar" all map to `searchMovies` because of meaning, not
words. Consequences for how tools are written:

- **Descriptions are the routing logic.** They say *when* to use the tool, not just what it does
  ("use it when the user names a movie ... including vague requests like 'a movie for tonight'").
- **Parameter descriptions say where values come from** ("movieId from searchMovies"), which teaches the model
  to chain tools instead of inventing ids.
- **Required parameters are real requirements.** `findTheatres` requires `city`; the system prompt tells the
  model to ask when a required value is unknown instead of guessing.
- **Results carry the ids for the next step** (`showId`, `movieId`) and pre-formatted values (₹, India time),
  so the model never converts paise or time zones itself.

## 3. The tool-execution cycle (one user message)

Spring AI can run tools internally, but we use **user-controlled tool execution**
(`internalToolExecutionEnabled = false`) so the loop is ours:

```
state  = load ConversationState;  state.turn++
history = chatMemory.get(conversationId)                // windowed, starts at a user message
messages = [System(instructions + today + state)] + history + [User(text)]
repeat up to maxToolRounds (6):
    response = chatClient.prompt(Prompt(messages, options{tools, toolContext, internalExecution=false})).call()
    if response has no tool calls:  save assistant text; return it
    result = toolCallingManager.executeToolCalls(prompt, response)   // runs the requested @Tool methods
    save AssistantMessage(tool calls) + ToolResponseMessage(results) to memory
    messages = result.conversationHistory()                         // the model sees the results next round
return a "could not complete" message (round limit)
```

Owning the loop gives us: a hard round limit (no runaway costs), a per-call audit trail (`toolCalls` in the API
response), persisted tool traffic (so the next message remembers what the backend said), and a place to enforce
the confirmation rules below. Multi-step chaining ("book 2 tickets for Interstellar at 7 PM tomorrow in Chennai"
→ `searchMovies` → `findShowtimes` → `checkSeatAvailability` → `prepareSeatHold`) happens naturally: each round
the model sees the previous results and decides the next call.

**Tool context.** Who the user is and the conversation state are passed via Spring AI's `ToolContext`, filled by
our code. The model never supplies a user id and cannot act as someone else.

**Failures never break the turn.** `GuardedToolCallback` wraps every tool:
- Malformed or mistyped JSON arguments → `INVALID_ARGUMENTS`. Spring AI is lenient with broken JSON, so the
  guard parses the arguments strictly first.
- Business errors pass through as their `ErrorCode` (`SEATS_UNAVAILABLE`, `CANCELLATION_WINDOW_CLOSED`...).
- Timeouts → `BACKEND_TIMEOUT`.
- Anything else → generic `TOOL_FAILED`, logged server-side. No SQL, class names or stack traces reach the model.

The model receives `{status:"error", errorCode, summary}` and explains or recovers. It is never handed an
exception it might paper over with an invented result.

## 4. Memory and context

Two layers, both persisted, both scoped to a conversation owned by one user:

1. **Conversation memory** — Spring AI `ChatMemory`, implemented over the existing `chatbot_messages` table,
   which already stores user / assistant / tool messages. A custom implementation, not `MessageWindowChatMemory`:
   - the stock window can cut between a tool call and its tool response (providers reject that history);
   - its repository rewrites the whole conversation on every save.

   Ours appends, and windows from a user message.
2. **Conversation state** — `ConversationState` JSON in `chatbot_sessions.context`: movie, city, date, theatre,
   show, ticket count, seats, booking id/status, pending confirmation. Written **only by tools, from backend
   results**, and rendered into the system prompt each turn, so "book two for 7 PM" works even after early
   messages leave the history window. Changing the movie clears the show; changing the show voids a prepared hold.

Isolation: a `conversationId` belongs to the user who created it; any other user gets `404`, the same rule as
bookings. Concurrency: one message per conversation at a time (a short lease on the session row), so a double-
submitted "yes" cannot be processed twice in parallel.

## 5. Booking safety

| Rule | Enforcement (server-side, not prompt-only) |
|---|---|
| No hold without explicit confirmation | `prepareSeatHold` only stores a `PendingAction` (seats, price, show) and shows a summary card; `confirmSeatHold(confirmationId)` is **rejected unless the action was prepared in an earlier user turn** — the model cannot prepare and confirm in one go |
| Show movie, theatre, time, seats, total first | the prepare result *is* that summary (computed by the backend from the live seat map) |
| Re-validate availability before committing | confirm calls `SeatLockService.lock`, which atomically re-checks every seat (Redis gate + conditional UPDATE) |
| No duplicate bookings | a pending action is single-use; the backend allows one active lock per user per show; one booking per lock; one message per conversation at a time |
| Never claim success without backend confirmation | confirmation status comes only from tool results; the prompt forbids claiming payment/confirmation unless `getBookingDetails` says `CONFIRMED` |
| Payment | `requestPayment` only returns a "Confirm & Pay" card; the payment API is called by the user's click. ArchUnit: the agent package must not depend on `payment` |
| Cancellation | same prepare → confirm gate; `BookingService.cancel` enforces the 2 h cutoff and triggers refunds |
| No secrets to the model | tools return curated records (no tokens, emails, internal ids beyond the ones needed); API keys only via env vars |

## 6. Tools

| Tool | Backs (existing API) | Increment |
|---|---|---|
| `searchMovies`, `getMovieDetails` | `GET /movies`, `/movies/search`, `/movies/{id}` | 1 ✅ |
| `findTheatres` | `GET /theatres?city=` | 1 ✅ |
| `findShowtimes` | `GET /shows?movieId&theatreId&city&date&from&to` | 1 ✅ |
| `checkSeatAvailability` (+ best-seat suggestion), `getSeatLayout` | `GET /shows/{id}`, `/shows/{id}/seats` | 1 ✅ |
| `prepareSeatHold`, `confirmSeatHold` | `POST /seat-locks` + `POST /bookings` | 2 ✅ |
| `getBookingDetails`, `listMyBookings` | `GET /bookings/{id}`, `/bookings/my` | 2 ✅ |
| `prepareCancellation`, `confirmCancellation` | `POST /bookings/{id}/cancel` | 2 ✅ |
| `requestPayment` (card only) | — (UI calls `POST /payments/orders`) | 2 ✅ |

### Confirmation gate details

- `confirmationId`: 8 random characters (no look-alike 0/O, 1/I), single use, 10 min TTL (`cinebot.agent.confirmation-ttl`).
- Rejections the model receives: `USER_CONFIRMATION_REQUIRED` (same turn), `NO_PENDING_CONFIRMATION` (wrong / used /
  other conversation's id, or wrong action type), `CONFIRMATION_EXPIRED`, `USER_DECLINED`.
- **A "no" is enforced, not interpreted.** Before the model sees the user's message, the gate withdraws the
  pending action when (a) the request carries `declineConfirmationId` (the card's "No" button: exactly that
  action), or (b) the typed text reads as a refusal (`no`, `nope`, `don't hold`, `not now`, `wait`, `never mind`...;
  "no problem" excluded). Confirming a declined id returns `USER_DECLINED`. The check is deliberately broad: a
  false positive only withdraws a free summary (the agent asks again); it never approves anything.
- A confirmation is single use: it is consumed when the confirm tool runs, but recorded as **executed** only when
  the hold / cancellation actually succeeded. A repeated confirmation of an executed id is a no-op that reports
  the booking *that action* held or cancelled (double "yes", client retry) — never a second hold. A repeat after a
  failure gets `NO_PENDING_CONFIRMATION`, never a false "already done".
- Choosing another show, or another movie, voids a prepared hold.
- `confirmSeatHold` re-checks seats atomically via `SeatLockService.lock`; if the booking can't be created the
  lock is released immediately. `prepareSeatHold` refuses when the user already has an open hold for the show.
- Residual risk, stated honestly: the gate guarantees the user *saw the summary and replied without refusing*;
  whether an ambiguous reply ("hmm, ok I guess") means "yes" is still the model's reading. That is why holds (free, auto-expiring) can be confirmed in chat, but
  payment cannot.

## 7. Configuration

```bash
AI_CHAT_PROVIDER=google-genai GEMINI_API_KEY=... ./mvnw spring-boot:run   # optional: GEMINI_MODEL=gemini-3.1-flash-lite
```

Without `AI_CHAT_PROVIDER` the provider is off (`spring.ai.model.chat=none`): the app and all tests run, and the
agent endpoint answers `503 AI_NOT_CONFIGURED`. Free Gemini keys: Google AI Studio. Temperature 0.2 (tool routing
should be consistent), one provider retry.

## 8. Testing

- `SeatSuggesterTest` — seat suggestion (unit).
- `AgentToolsIT` — every tool called the way the model calls it (`ToolCallback.call(jsonArgs, toolContext)`),
  against Postgres: chaining fills the state; bad arguments, unknown ids, past dates, started shows and empty
  results come back as structured errors, never exceptions or leaked internals.
- Increment 3 — full conversations with a **scripted `ChatModel`** (returns predetermined tool calls), so the
  loop, memory, confirmation gate and isolation are tested deterministically without an API key; plus an
  opt-in live smoke test with a real key.

## 9. Increments

1. ✅ Spring AI + Gemini config, tool infrastructure (guard, errors, state), read-only tools, tests.
2. ✅ Booking tools + `ConfirmationGate` (prepare/confirm, cancel, payment card) + `BookingToolsIT` (9 tests;
   mutation-checked: without the later-turn rule, holds and cancellations go through unconfirmed and 2 tests fail).
3. Agent service loop, persistent memory, `/api/v1/agent/chat`, removal of the Step 7 loop, widget update,
   scripted-model conversation tests, live check with a Gemini key.
