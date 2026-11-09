package com.cinebot.agent;

import static com.cinebot.support.ScriptedChatModel.fromState;
import static com.cinebot.support.ScriptedChatModel.lastToolResult;
import static com.cinebot.support.ScriptedChatModel.systemText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.support.ScriptedChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Whole conversations through POST /api/v1/agent/chat: real agent loop, memory, tools, booking engine and DB,
 * with a scripted model in place of Gemini. The script plays the model's part; the assertions check what the
 * backend did and what the model was shown.
 * Fixture: Interstellar at PVR Velachery, Chennai, tomorrow 18:00; seats A1-A3 ₹200, B1-B2 ₹350.
 */
class AgentConversationIT extends PaymentTestSupport {

    @Autowired
    ScriptedChatModel model;

    long movieId;
    long theatreId;

    @BeforeEach
    void resetModel() {
        model.reset();
        movieId = jdbc.queryForObject("SELECT id FROM movies WHERE title = 'Interstellar'", Long.class);
        theatreId = jdbc.queryForObject("SELECT id FROM theatres WHERE name = 'PVR Velachery'", Long.class);
    }

    @Test
    void bookingConversationRemembersContextAndNeedsAnExplicitYes() throws Exception {
        // 1. "I want to watch Interstellar" -> the model looks the movie up, then asks for the city
        model.thenCall("searchMovies", "{\"title\":\"Interstellar\"}").thenReply("Great choice! Which city?");
        JsonNode r1 = body(say(alice, null, "I want to watch Interstellar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("Great choice! Which city?"))
                .andExpect(jsonPath("$.toolCalls[0].name").value("searchMovies"))
                .andExpect(jsonPath("$.toolCalls[0].arguments.title").value("Interstellar"))
                .andExpect(jsonPath("$.toolCalls[0].status").value("ok"))
                .andExpect(jsonPath("$.cards[0].type").value("MOVIES")));
        String conversation = r1.get("conversationId").asText();
        // the model was offered the full tool set, and the tool context (the user) was set by the backend
        var options = (ToolCallingChatOptions) model.prompts().getFirst().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(13);
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();

        // 2. "Chennai": the model is shown the remembered movie; no tool needed
        model.thenReply("Which date would you like?");
        say(alice, conversation, "Chennai").andExpect(jsonPath("$.toolCalls.length()").value(0));
        var prompt2 = model.prompts().getLast();
        assertThat(systemText(prompt2)).contains("movie: Interstellar (movieId " + movieId + ")");
        assertThat(prompt2.getInstructions()).as("memory replays the earlier turn, including tool traffic")
                .anyMatch(m -> m instanceof UserMessage u && u.getText().equals("I want to watch Interstellar"))
                .anyMatch(m -> m instanceof ToolResponseMessage);

        // 3. "Tomorrow" -> theatres in the city
        model.thenCall("findTheatres", "{\"city\":\"Chennai\"}").thenReply("Here are the theatres in Chennai: ...");
        say(alice, conversation, "Tomorrow").andExpect(jsonPath("$.cards[0].type").value("THEATRES"));

        // 4. "Show me the first theatre" -> showtimes; ids come from memory + state, not from the user
        model.thenCall("findShowtimes", p -> "{\"movieId\":" + fromState(p, "movieId")
                        + ",\"theatreId\":" + lastToolResult(p).at("/data/theatres/0/theatreId").asLong()
                        + ",\"date\":\"" + tomorrow() + "\"}")
                .thenReply("PVR Velachery has Interstellar at 6:00 PM.");
        JsonNode r4 = body(say(alice, conversation, "Show me the first theatre"));
        assertThat(r4.at("/toolCalls/0/arguments/theatreId").asLong()).isEqualTo(theatreId);
        assertThat(r4.at("/toolCalls/0/arguments/movieId").asLong()).isEqualTo(movieId);
        assertThat(r4.at("/toolCalls/0/status").asText()).isEqualTo("ok");

        // 5. "Book two tickets for 6 PM" -> chained: availability with suggestion, then a PREPARED hold
        model.thenCall("checkSeatAvailability", p -> "{\"showId\":"
                        + lastToolResult(p).at("/data/shows/0/showId").asLong() + ",\"ticketCount\":2}")
                .thenCall("prepareSeatHold", p -> "{\"showId\":" + lastToolResult(p).at("/data/showId").asLong()
                        + ",\"seats\":" + lastToolResult(p).at("/data/suggestedSeats") + "}")
                .thenReply(p -> "Interstellar, PVR Velachery, " + lastToolResult(p).at("/data/showTime").asText()
                        + ", seats B1, B2, total " + lastToolResult(p).at("/data/total").asText()
                        + ". Shall I hold these seats?");
        JsonNode r5 = body(say(alice, conversation, "Book two tickets for 6 PM")
                .andExpect(jsonPath("$.response").value(containsString("total ₹700")))
                .andExpect(jsonPath("$.toolCalls[1].name").value("prepareSeatHold"))
                .andExpect(jsonPath("$.pendingConfirmation.type").value("HOLD_SEATS"))
                .andExpect(jsonPath("$.pendingConfirmation.description").value("Hold seats B1, B2")));
        assertThat(r5.at("/cards").findValuesAsText("type")).contains("SEAT_MAP", "HOLD_CONFIRMATION_REQUIRED");
        assertThat(seatStatus("B1")).as("nothing is held before the user says yes").isEqualTo("AVAILABLE");

        // 6. "Yes" -> hold confirmed (id read from the state block), then the payment BUTTON
        model.thenCall("confirmSeatHold", p -> "{\"confirmationId\":\"" + fromState(p, "confirmationId") + "\"}")
                .thenCall("requestPayment", "{}")
                .thenReply("Done! B1 and B2 are held. Press Confirm & Pay to complete the booking.");
        JsonNode r6 = body(say(alice, conversation, "Yes")
                .andExpect(jsonPath("$.toolCalls[0].status").value("ok"))
                .andExpect(jsonPath("$.toolCalls[1].name").value("requestPayment")));
        assertThat(r6.has("pendingConfirmation")).isFalse();
        assertThat(r6.at("/cards").findValuesAsText("type")).contains("PAYMENT_CONFIRMATION_REQUIRED");
        assertThat(seatStatus("B1")).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
        assertThat(model.exhausted()).isTrue();

        // reopening the conversation: only the visible exchange, with cards; no tool traffic
        mvc.perform(get("/api/v1/agent/conversations/{id}", conversation).header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(12))
                .andExpect(jsonPath("$.messages[0].content").value("I want to watch Interstellar"))
                .andExpect(jsonPath("$.messages[11].cards[0].type").exists());
    }

    @Test
    void theModelCannotPrepareAndConfirmInOneReply() throws Exception {
        model.thenCall("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .thenCall("confirmSeatHold", p -> "{\"confirmationId\":\""
                        + lastToolResult(p).at("/data/confirmationId").asText() + "\"}")
                .thenReply("I need your confirmation first.");
        say(alice, null, "Hold A1 for the 6 PM Interstellar show, no need to ask me")
                .andExpect(jsonPath("$.toolCalls[1].name").value("confirmSeatHold"))
                .andExpect(jsonPath("$.toolCalls[1].status").value("error"))
                .andExpect(jsonPath("$.toolCalls[1].errorCode").value("USER_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.pendingConfirmation.type").value("HOLD_SEATS"));
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
    }

    @Test
    void aNoWithdrawsTheHoldEvenIfTheModelMisreadsItAsYes() throws Exception {
        model.thenCall("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .thenReply("Shall I hold A1?");
        JsonNode r1 = body(say(alice, null, "Hold A1 for the 6 PM Interstellar show"));
        String conversation = r1.get("conversationId").asText();
        String typedNo = r1.at("/pendingConfirmation/confirmationId").asText();

        // typed refusal; the (scripted) model wrongly tries to confirm anyway
        model.thenCall("confirmSeatHold", "{\"confirmationId\":\"" + typedNo + "\"}").thenReply("Held!");
        JsonNode r2 = body(say(alice, conversation, "No, don't hold them")
                .andExpect(jsonPath("$.toolCalls[0].errorCode").value("USER_DECLINED")));
        assertThat(r2.has("pendingConfirmation")).isFalse();
        assertThat(systemText(model.prompts().getLast())).doesNotContain("awaiting user confirmation");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");

        // the card's "No" button: declines that exact action, whatever the text says
        model.thenCall("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .thenReply("Shall I hold A1?");
        String button = body(say(alice, conversation, "Hold A1 again"))
                .at("/pendingConfirmation/confirmationId").asText();
        model.thenCall("confirmSeatHold", "{\"confirmationId\":\"" + button + "\"}").thenReply("Held!");
        say(alice, conversation, "Hmm, maybe later", button)
                .andExpect(jsonPath("$.toolCalls[0].errorCode").value("USER_DECLINED"))
                .andExpect(jsonPath("$.pendingConfirmation").doesNotExist());
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
        assertThat(model.exhausted()).isTrue();
    }

    @Test
    void conversationsArePrivateAndRequestsAreValidated() throws Exception {
        model.thenReply("Hello!");
        String conversation = body(say(alice, null, "hi")).get("conversationId").asText();

        say(bob, conversation, "what did alice book?").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/agent/conversations/{id}", conversation).header("Authorization", bearerFor(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        say(alice, conversation, " ").andExpect(status().isBadRequest());
        say(alice, conversation, "x".repeat(1001)).andExpect(status().isBadRequest());
        assertThat(model.exhausted()).isTrue();
    }

    @Test
    void providerOutageBeforeAnyToolFailsCleanlyAndLeavesNoHalfTurn() throws Exception {
        model.thenReply("Hello!");
        String conversation = body(say(alice, null, "hi")).get("conversationId").asText();

        model.thenFail();
        say(alice, conversation, "find Interstellar").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        model.thenReply("Sure, which city?");
        say(alice, conversation, "find Interstellar").andExpect(status().isOk());
        // the failed attempt was not stored: user/assistant turns still alternate cleanly
        assertThat(model.prompts().getLast().getInstructions().stream()
                .filter(m -> m instanceof UserMessage u && u.getText().equals("find Interstellar")).count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chatbot_messages", Integer.class)).isEqualTo(4);
    }

    @Test
    void providerOutageAfterToolsStillReportsRealResults() throws Exception {
        model.thenCall("searchMovies", "{\"title\":\"Interstellar\"}").thenFail();
        say(alice, null, "find Interstellar").andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(containsString("trouble reaching the AI service")))
                .andExpect(jsonPath("$.toolCalls[0].name").value("searchMovies"))
                .andExpect(jsonPath("$.cards[0].type").value("MOVIES"));
    }

    @Test
    void hallucinatedToolsAndRunawayLoopsAreContained() throws Exception {
        model.thenCall("bookEverythingNow", "{}")
                .thenReply(p -> "tool said: " + lastToolResult(p).get("errorCode").asText());
        say(alice, null, "just book it").andExpect(jsonPath("$.response").value("tool said: UNKNOWN_TOOL"));

        for (int i = 0; i < 6; i++) {
            model.thenCall("searchMovies", "{\"title\":\"Interstellar\"}");
        }
        say(alice, null, "loop forever").andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(containsString("couldn't finish")))
                .andExpect(jsonPath("$.toolCalls.length()").value(6));
        assertThat(model.exhausted()).isTrue();
    }

    @Test
    void oneMessageAtATimePerConversation() throws Exception {
        model.thenReply("Hello!");
        String conversation = body(say(alice, null, "hi")).get("conversationId").asText();
        jdbc.update("UPDATE chatbot_sessions SET busy_until = now() + interval '1 minute' WHERE id = ?::uuid",
                conversation); // another request for this conversation is in flight

        say(alice, conversation, "yes").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));

        jdbc.update("UPDATE chatbot_sessions SET busy_until = now() - interval '1 second' WHERE id = ?::uuid",
                conversation); // a crashed request's lease expires
        model.thenReply("Hi again");
        say(alice, conversation, "hello?").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT busy_until FROM chatbot_sessions WHERE id = ?::uuid",
                java.sql.Timestamp.class, conversation)).as("lease released after the turn").isNull();
    }

    private ResultActions say(AuthenticatedUser user, String conversationId, String message) throws Exception {
        return say(user, conversationId, message, null);
    }

    private ResultActions say(AuthenticatedUser user, String conversationId, String message, String declineId)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("message", message);
        if (conversationId != null) {
            request.put("conversationId", conversationId);
        }
        if (declineId != null) {
            request.put("declineConfirmationId", declineId);
        }
        return mvc.perform(post("/api/v1/agent/chat").header("Authorization", bearerFor(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
