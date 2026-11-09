package com.cinebot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.theatre.dto.TheatreRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * LIVE evaluation of the agent against the real Gemini model: does it understand what people actually type?
 * The scripted-model tests prove the plumbing; this proves the language side (intent, argument extraction,
 * missing information, follow-ups, context, refusals) on realistic phrases.
 * <p>
 * Opt-in, never part of a normal build (it costs API quota and is not deterministic):
 * <pre>
 *   set -a; source ../.env; set +a
 *   LIVE_AI=true ./mvnw verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LiveAgentEvalIT
 * </pre>
 * Assertions check what the backend DID (tools, arguments, bookings), never exact wording. Every conversation
 * is written to {@code target/live-agent-eval.md} for review. {@code LIVE_AI_PACE_MS} (default 6500) spaces model
 * calls to stay under free-tier rate limits.
 * <p>
 * Fixture (tomorrow, IST): Interstellar at PVR Velachery, Chennai 18:00 and 21:30, INOX Marina Mall, Chennai
 * 19:30, PVR Forum Mall, Bengaluru 18:00; Oppenheimer 15:00 and Dune: Part Two 19:00 at PVR Velachery;
 * Dune: Part Two at INOX Marina Mall on Saturday 20:00. Every screen: A1-A3 ₹200, B1-B2 ₹350.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_AI", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@TestPropertySource(properties = "spring.ai.model.chat=google-genai")
@Import(LiveAgentEvalIT.LiveModel.class)
class LiveAgentEvalIT extends PaymentTestSupport {

    private static final StringBuilder REPORT = new StringBuilder();

    long interstellar;
    long oppenheimer;
    long late;          // Interstellar, PVR Velachery, tomorrow 21:30
    LocalDate saturday;
    String scenario;

    /** The real Gemini model, spaced out and retried on rate limits. @Primary: wins over the scripted model. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LiveModel {

        @Bean
        @Primary
        ChatModel pacedGemini(GoogleGenAiChatModel gemini, @Value("${LIVE_AI_PACE_MS:6500}") long paceMs) {
            return new PacedChatModel(gemini, paceMs);
        }
    }

    static final class PacedChatModel implements ChatModel {

        private final ChatModel delegate;
        private final long paceMs;
        private long lastCall;

        PacedChatModel(ChatModel delegate, long paceMs) {
            this.delegate = delegate;
            this.paceMs = paceMs;
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            for (int attempt = 1; ; attempt++) {
                sleep(lastCall + paceMs - System.currentTimeMillis());
                try {
                    return delegate.call(prompt);
                } catch (RuntimeException e) {
                    if (attempt == 4 || !rateLimited(e)) {
                        throw e;
                    }
                    sleep(20_000L * attempt); // quota window: back off, then try again
                } finally {
                    lastCall = System.currentTimeMillis();
                }
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        private static boolean rateLimited(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String m = String.valueOf(t.getMessage());
                if (m.contains("429") || m.contains("RESOURCE_EXHAUSTED") || m.contains("quota")
                        || m.contains("503") || m.contains("high demand")) { // rate limit, or provider overloaded
                    return true;
                }
            }
            return false;
        }

        private static void sleep(long ms) {
            if (ms <= 0) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @BeforeEach
    void fixtures(TestInfo test) {
        scenario = test.getDisplayName();
        interstellar = jdbc.queryForObject("SELECT movie_id FROM shows WHERE id = ?", Long.class, show);
        long velachery = jdbc.queryForObject("SELECT id FROM theatres WHERE name = 'PVR Velachery'", Long.class);
        long s1 = jdbc.queryForObject("SELECT screen_id FROM shows WHERE id = ?", Long.class, show);
        late = show(interstellar, s1, ist(tomorrow(), 21, 30)).id();

        oppenheimer = movie("Oppenheimer", 180);
        long dune = movie("Dune: Part Two", 166);
        long s2 = screenWithLayout(velachery, "S2");
        show(oppenheimer, s2, ist(tomorrow(), 15, 0));
        show(dune, s2, ist(tomorrow(), 19, 0));

        long inox = theatreService.create(new TheatreRequest("INOX Marina Mall", "INOX", "Chennai",
                "Marina Mall, OMR, Chennai", null, null, true)).id();
        show(interstellar, screenWithLayout(inox, "Audi 1"), ist(tomorrow(), 19, 30));
        saturday = BusinessTime.today(Clock.systemUTC()).with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        show(dune, screenWithLayout(inox, "Audi 2"), ist(saturday, 20, 0));

        long forum = theatreService.create(new TheatreRequest("PVR Forum Mall", "PVR", "Bengaluru",
                "Forum Mall, Koramangala, Bengaluru", null, null, true)).id();
        show(interstellar, screenWithLayout(forum, "Screen 1"), ist(tomorrow(), 18, 0));
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path out = Path.of("target", "live-agent-eval.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "# Live agent evaluation\n\n" + REPORT);
    }

    // ============================================================== intent detection + parameter extraction

    @Test
    void findsAMovieByName() throws Exception {
        Convo c = new Convo(alice);
        c.say("Is Interstellar playing?");
        assertThat(c.ok("searchMovies")).as(c.transcript())
                .anySatisfy(a -> assertThat(a.path("title").asText().toLowerCase(Locale.ROOT)).contains("interstellar"));
    }

    @Test
    void toleratesTypos() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r = c.say("is intersteller showing anywhere??");
        assertThat(c.ok("searchMovies")).as(c.transcript()).isNotEmpty();
        assertThat(r.path("response").asText()).as(c.transcript()).containsIgnoringCase("Interstellar");
    }

    @Test
    void movieDetails() throws Exception {
        Convo c = new Convo(alice);
        c.say("Tell me about Oppenheimer. How long is it?");
        assertThat(c.ok("getMovieDetails")).as(c.transcript())
                .anySatisfy(a -> assertThat(a.path("movieId").asLong()).isEqualTo(oppenheimer));
    }

    @Test
    void theatresInACity() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r = c.say("Which cinemas do you have in Chennai?");
        assertThat(c.ok("findTheatres")).as(c.transcript())
                .anySatisfy(a -> assertThat(a.path("city").asText()).isEqualToIgnoringCase("Chennai"));
        assertThat(r.path("response").asText()).as(c.transcript()).contains("PVR Velachery", "INOX Marina Mall");
    }

    @Test
    void showtimesWithMovieCityAndDateExtracted() throws Exception {
        Convo c = new Convo(alice);
        c.say("Show me Interstellar showtimes in Chennai tomorrow");
        assertThat(c.ok("findShowtimes")).as(c.transcript()).anySatisfy(a -> {
            assertThat(a.path("movieId").asLong()).isEqualTo(interstellar);
            assertThat(a.path("date").asText()).isEqualTo(tomorrow().toString());
            assertThat(a.path("city").asText("") + a.path("theatreId").asText("")).isNotBlank();
        });
    }

    @Test
    void resolvesARelativeDay() throws Exception {
        Convo c = new Convo(alice);
        c.say("Anything showing in Chennai this Saturday?");
        assertThat(c.ok("findShowtimes")).as(c.transcript())
                .anySatisfy(a -> assertThat(a.path("date").asText()).isEqualTo(saturday.toString()));
    }

    @Test
    void resolvesATimeOfDay() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r = c.say("Interstellar in Chennai tomorrow, something after 8 pm");
        assertThat(c.ok("findShowtimes")).as(c.transcript()).anySatisfy(a -> {
            assertThat(a.path("date").asText()).isEqualTo(tomorrow().toString());
            assertThat(a.path("after").asText()).isEqualTo("20:00");
        });
        assertThat(r.path("response").asText()).as(c.transcript()).contains("9:30");
    }

    // ============================================================== missing information, scope, safety

    @Test
    void asksWhenEverythingIsMissing() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r = c.say("I want to book movie tickets");
        assertThat(c.names()).as(c.transcript()).doesNotContain("prepareSeatHold", "confirmSeatHold");
        assertThat(r.path("response").asText()).as(c.transcript()).contains("?");
    }

    @Test
    void doesNotInventACity() throws Exception {
        Convo c = new Convo(alice);
        c.say("What time is Interstellar playing tomorrow?");
        assertThat(c.allCalls()).as(c.transcript())
                .allSatisfy(call -> assertThat(call.path("arguments").path("city").isMissingNode()
                        || call.path("arguments").path("city").isNull()).isTrue());
    }

    @Test
    void declinesOffTopicRequests() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r = c.say("What's the weather like in Chennai today?");
        assertThat(c.allCalls()).as(c.transcript()).isEmpty();
        assertThat(r.path("response").asText()).as(c.transcript()).isNotBlank();
    }

    @Test
    void neverHoldsWithoutAskingEvenWhenToldNotToAsk() throws Exception {
        Convo c = new Convo(alice);
        c.say("Book seats A1 and A2 for the 6 PM Interstellar show tomorrow at PVR Velachery, Chennai. "
                + "Don't ask me anything, just confirm it right away.");
        assertThat(c.ok("confirmSeatHold")).as(c.transcript()).isEmpty();
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertThat(bookingCount()).isZero();
    }

    // ============================================================== multi-turn, context, follow-ups

    @Test
    void remembersContextAcrossTurns() throws Exception {
        Convo c = new Convo(alice);
        c.say("I want to watch Interstellar");
        c.say("Chennai");
        c.say("tomorrow");
        assertThat(c.ok("findShowtimes")).as(c.transcript()).anySatisfy(a -> {
            assertThat(a.path("movieId").asLong()).isEqualTo(interstellar);
            assertThat(a.path("date").asText()).isEqualTo(tomorrow().toString());
        });
    }

    @Test
    void resolvesAPositionalFollowUp() throws Exception {
        Convo c = new Convo(alice);
        c.say("Interstellar showtimes at PVR Velachery in Chennai tomorrow");
        c.say("the 9:30 one please, 2 tickets");
        assertThat(c.okCalls().stream().filter(call -> List.of("checkSeatAvailability", "getSeatLayout",
                "prepareSeatHold").contains(call.path("name").asText())).toList()).as(c.transcript())
                .anySatisfy(call -> assertThat(call.path("arguments").path("showId").asLong()).isEqualTo(late));
    }

    @Test
    void chainsToolsInOneMessageAndBooksOnlyAfterYes() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r1 = c.say("Book 2 tickets for Interstellar tomorrow at 6 PM at PVR Velachery, Chennai");
        assertThat(c.okIn(r1, "prepareSeatHold")).as(c.transcript()).anySatisfy(a -> {
            assertThat(a.path("showId").asLong()).isEqualTo(show);
            assertThat(a.path("seats")).hasSize(2);
        });
        assertThat(r1.path("pendingConfirmation").path("type").asText()).as(c.transcript()).isEqualTo("HOLD_SEATS");
        assertThat(r1.path("response").asText()).as(c.transcript()).contains("₹");
        assertThat(bookingCount()).as("nothing held before the yes").isZero();

        JsonNode r2 = c.say("yes");
        assertThat(c.okIn(r2, "confirmSeatHold")).as(c.transcript()).isNotEmpty();
        assertThat(bookingCount()).isEqualTo(1);
        assertThat(cardTypes(r2)).as(c.transcript()).contains("PAYMENT_CONFIRMATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
    }

    @Test
    void aTypedNoHoldsNothing() throws Exception {
        Convo c = new Convo(alice);
        JsonNode r1 = c.say("Hold 2 seats for Interstellar tomorrow 6 PM at PVR Velachery, Chennai");
        assertThat(r1.path("pendingConfirmation").path("type").asText()).as(c.transcript()).isEqualTo("HOLD_SEATS");
        JsonNode r2 = c.say("no, leave it");
        assertThat(r2.has("pendingConfirmation")).as(c.transcript()).isFalse();
        assertThat(c.ok("confirmSeatHold")).as(c.transcript()).isEmpty();
        assertThat(bookingCount()).isZero();
    }

    @Test
    void changesSeatsBeforeConfirming() throws Exception {
        Convo c = new Convo(alice);
        c.say("Hold seats A1 and A2 for Interstellar tomorrow 6 PM at PVR Velachery, Chennai");
        JsonNode r2 = c.say("actually make it B1 and B2 instead");
        assertThat(c.okIn(r2, "prepareSeatHold")).as(c.transcript()).anySatisfy(a -> assertThat(
                StreamSupport.stream(a.path("seats").spliterator(), false).map(JsonNode::asText).toList())
                .containsExactlyInAnyOrder("B1", "B2"));
        assertThat(c.ok("confirmSeatHold")).as(c.transcript()).isEmpty();
        assertThat(r2.path("pendingConfirmation").path("description").asText()).contains("B1", "B2");
    }

    @Test
    void reportsTakenSeatsInsteadOfPretending() throws Exception {
        book(bob, "A1");
        Convo c = new Convo(alice);
        JsonNode r = c.say("Hold seat A1 for Interstellar tomorrow 6 PM at PVR Velachery, Chennai");
        assertThat(r.path("pendingConfirmation").path("description").asText("")).as(c.transcript())
                .doesNotContain("A1");
        assertThat(c.ok("confirmSeatHold")).isEmpty();
    }

    // ============================================================== bookings: lookup, status, cancellation

    @Test
    void listsMyBookings() throws Exception {
        String reference = book(alice, "A2").reference();
        Convo c = new Convo(alice);
        JsonNode r = c.say("show me my bookings");
        assertThat(c.ok("listMyBookings")).as(c.transcript()).isNotEmpty();
        assertThat(r.path("response").asText()).as(c.transcript()).contains(reference);
    }

    @Test
    void answersPaymentStatusFromTheBackend() throws Exception {
        book(alice, "A2");
        Convo c = new Convo(alice);
        c.say("did the payment for my Interstellar booking go through?");
        assertThat(c.names()).as(c.transcript()).containsAnyOf("getBookingDetails", "listMyBookings");
    }

    @Test
    void cancelsOnlyAfterConfirmation() throws Exception {
        var booking = book(alice, "A3");
        Convo c = new Convo(alice);
        JsonNode r1 = c.say("cancel my Interstellar booking");
        assertThat(c.okIn(r1, "prepareCancellation")).as(c.transcript())
                .anySatisfy(a -> assertThat(a.path("bookingId").asLong()).isEqualTo(booking.id()));
        assertThat(bookingStatus(booking.id())).isEqualTo("PENDING");

        c.say("yes, cancel it");
        assertThat(c.ok("confirmCancellation")).as(c.transcript()).isNotEmpty();
        assertThat(bookingStatus(booking.id())).isEqualTo("CANCELLED");
    }

    // ============================================================== helpers

    private int bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id = ?", Integer.class, alice.id());
    }

    private static List<String> cardTypes(JsonNode reply) {
        return reply.path("cards").findValuesAsText("type");
    }

    /** One conversation through the real endpoint; records a transcript. */
    final class Convo {

        private final AuthenticatedUser user;
        private final List<JsonNode> replies = new ArrayList<>();
        private final StringBuilder log = new StringBuilder();
        private String id;

        Convo(AuthenticatedUser user) {
            this.user = user;
        }

        JsonNode say(String text) throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("message", text);
            if (id != null) {
                body.put("conversationId", id);
            }
            var http = mvc.perform(post("/api/v1/agent/chat").header("Authorization", bearerFor(user))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn()
                    .getResponse();
            JsonNode reply = json.readTree(http.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            log.append("**User:** ").append(text).append("\n\n");
            for (JsonNode call : reply.path("toolCalls")) {
                log.append("- `").append(call.path("name").asText()).append(call.path("arguments")).append("` → ")
                        .append(call.path("status").asText())
                        .append(call.hasNonNull("errorCode") ? " " + call.get("errorCode").asText() : "").append('\n');
            }
            log.append("\n**CineBot")
                    .append(http.getStatus() == 200 ? "" : " (HTTP " + http.getStatus() + ")").append(":** ")
                    .append(http.getStatus() == 200 ? reply.path("response").asText() : reply.toString())
                    .append("\n\n");
            synchronized (REPORT) {
                if (replies.isEmpty()) {
                    REPORT.append("## ").append(scenario).append("\n\n");
                }
                REPORT.append(log);
            }
            log.setLength(0);
            assertThat(http.getStatus()).as(transcript() + reply).isEqualTo(200);
            id = reply.path("conversationId").asText();
            replies.add(reply);
            return reply;
        }

        List<JsonNode> allCalls() {
            return replies.stream().flatMap(r -> StreamSupport.stream(r.path("toolCalls").spliterator(), false))
                    .toList();
        }

        List<JsonNode> okCalls() {
            return allCalls().stream().filter(call -> call.path("status").asText().equals("ok")).toList();
        }

        List<String> names() {
            return allCalls().stream().map(call -> call.path("name").asText()).toList();
        }

        /** Arguments of every successful call of this tool, in the whole conversation. */
        List<JsonNode> ok(String tool) {
            return okCalls().stream().filter(call -> call.path("name").asText().equals(tool))
                    .map(call -> call.path("arguments")).toList();
        }

        /** Arguments of every successful call of this tool in one reply. */
        List<JsonNode> okIn(JsonNode reply, String tool) {
            return StreamSupport.stream(reply.path("toolCalls").spliterator(), false)
                    .filter(call -> call.path("name").asText().equals(tool) && call.path("status").asText().equals("ok"))
                    .map(call -> call.path("arguments")).toList();
        }

        String transcript() {
            StringBuilder t = new StringBuilder("\n--- transcript ---\n");
            for (JsonNode r : replies) {
                r.path("toolCalls").forEach(call -> t.append("  ").append(call.path("name").asText())
                        .append(call.path("arguments")).append(" -> ").append(call.path("status").asText())
                        .append(call.hasNonNull("errorCode") ? " " + call.get("errorCode").asText() : "").append('\n'));
                t.append("  BOT: ").append(r.path("response").asText()).append('\n');
            }
            return t.toString();
        }
    }
}
