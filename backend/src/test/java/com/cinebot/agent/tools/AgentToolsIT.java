package com.cinebot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.cinebot.agent.config.AgentToolsConfig.AgentToolbox;
import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.support.VenueTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Calls the agent's tools exactly the way the model does: through Spring AI ToolCallbacks with a JSON argument
 * string, against the real services and database. No LLM involved, so every result is deterministic.
 */
class AgentToolsIT extends VenueTestSupport {

    @Autowired
    AgentToolbox toolbox;
    @Autowired
    ObjectMapper json;

    AuthenticatedUser alice;
    long movieId;
    long theatreId;
    long showId;
    AgentTurn turn;

    @BeforeEach
    void fixtures() {
        alice = user("alice");
        movieId = movie("Interstellar", 169);
        theatreId = theatre("PVR Velachery", "Chennai");
        showId = show(movieId, screenWithLayout(theatreId, "Audi 1"), ist(tomorrow(), 19, 0)).id();
        turn = new AgentTurn(alice, new ConversationState(), Instant.now());
    }

    @Test
    void toolDefinitionsAreWhatTheModelReasonsOver() throws Exception {
        assertThat(toolbox.names()).containsExactlyInAnyOrder("searchMovies", "getMovieDetails", "findTheatres",
                "findShowtimes", "checkSeatAvailability", "getSeatLayout", "prepareSeatHold", "confirmSeatHold",
                "requestPayment", "getBookingDetails", "listMyBookings", "prepareCancellation", "confirmCancellation");
        for (ToolCallback tool : toolbox.callbacks()) {
            var def = tool.getToolDefinition();
            assertThat(def.description()).as(def.name()).hasSizeGreaterThan(60);
            JsonNode schema = json.readTree(def.inputSchema());
            assertThat(schema.at("/properties").has("toolContext")).as("ToolContext is not a model argument").isFalse();
        }
        assertThat(required("findTheatres")).containsExactly("city");
        assertThat(required("checkSeatAvailability")).containsExactly("showId");
        assertThat(required("searchMovies")).isEmpty();
        assertThat(required("prepareSeatHold")).containsExactlyInAnyOrder("showId", "seats");
        assertThat(required("confirmSeatHold")).containsExactly("confirmationId");
    }

    @Test
    void chainedLookupsFillTheConversationState() throws Exception {
        JsonNode movies = call("searchMovies", "{\"title\":\"interstel\"}");
        assertThat(movies.at("/status").asText()).isEqualTo("ok");
        assertThat(movies.at("/data/movies/0/movieId").asLong()).isEqualTo(movieId);

        JsonNode shows = call("findShowtimes",
                "{\"movieId\":" + movieId + ",\"city\":\"Chennai\",\"date\":\"" + tomorrow() + "\",\"after\":\"18:00\"}");
        assertThat(shows.at("/data/shows/0/showId").asLong()).isEqualTo(showId);
        assertThat(shows.at("/data/shows/0/time").asText()).isEqualTo("7:00 PM");      // India time, preformatted
        assertThat(shows.at("/data/shows/0/fromPrice").asText()).isEqualTo("₹200");    // rupees, not paise

        JsonNode seats = call("checkSeatAvailability", "{\"showId\":" + showId + ",\"ticketCount\":2}");
        assertThat(seats.at("/data/availableSeats").asInt()).isEqualTo(5);
        assertThat(seats.at("/data/suggestedSeats").toString()).isEqualTo("[\"B1\",\"B2\"]");
        assertThat(seats.at("/data/suggestedTotal").asText()).isEqualTo("₹700");
        assertThat(seats.at("/data/suggestedSeatsAdjacent").asBoolean()).isTrue();

        // 4 of 5 seats free, but no 4 side by side: suggested anyway, flagged so the model tells the user
        JsonNode apart = call("checkSeatAvailability", "{\"showId\":" + showId + ",\"ticketCount\":4}");
        assertThat(apart.at("/data/suggestedSeatsAdjacent").asBoolean()).isFalse();

        ConversationState state = turn.state();
        assertThat(state.getMovieId()).isEqualTo(movieId);
        assertThat(state.getCity()).isEqualTo("Chennai");
        assertThat(state.getDate()).isEqualTo(tomorrow().toString());
        assertThat(state.getTheatreId()).isEqualTo(theatreId);
        assertThat(state.getShowId()).isEqualTo(showId);
        assertThat(state.getTicketCount()).isEqualTo(4); // latest stated count wins
        assertThat(state.describeForModel()).contains("Interstellar (movieId " + movieId + ")",
                "PVR Velachery", "showId " + showId, "tickets: 4");

        assertThat(turn.trace()).extracting(AgentTurn.ToolTrace::name)
                .containsExactly("searchMovies", "findShowtimes", "checkSeatAvailability", "checkSeatAvailability");
        assertThat(turn.cards()).extracting(AgentCard::type).containsExactly(AgentCard.Type.MOVIES,
                AgentCard.Type.SHOWS, AgentCard.Type.SEAT_MAP, AgentCard.Type.SEAT_MAP);
    }

    @Test
    void layoutListsRowsWithFreeAndTakenSeats() throws Exception {
        jdbc.update("UPDATE show_seats SET status = 'BLOCKED' WHERE id = ?", seat(showId, "A2"));
        JsonNode layout = call("getSeatLayout", "{\"showId\":" + showId + "}");
        assertThat(layout.at("/data/rows/0/row").asText()).isEqualTo("A");
        assertThat(layout.at("/data/rows/0/available").toString()).isEqualTo("[\"A1\",\"A3\"]");
        assertThat(layout.at("/data/rows/0/unavailable").toString()).isEqualTo("[\"A2\"]");
        assertThat(layout.at("/data/rows/1/seatType").asText()).isEqualTo("PREMIUM");
    }

    @Test
    void badArgumentsAndBackendErrorsComeBackAsStructuredErrorsNotExceptions() throws Exception {
        assertError(call("findShowtimes", "{\"city\":\"Chennai\",\"date\":\"tomorrow\"}"), "INVALID_ARGUMENTS");
        assertError(call("findShowtimes", "{}"), "INVALID_ARGUMENTS");
        assertError(call("findTheatres", "{}"), "INVALID_ARGUMENTS");
        assertError(call("checkSeatAvailability", "{\"showId\":" + showId + ",\"ticketCount\":11}"), "INVALID_ARGUMENTS");
        assertError(call("checkSeatAvailability", "{\"showId\":\"not-a-number\"}"), "INVALID_ARGUMENTS");
        assertError(call("checkSeatAvailability", "{\"showId\": 12, oops"), "INVALID_ARGUMENTS");
        assertError(call("checkSeatAvailability", "{\"showId\":999999}"), "SHOW_NOT_FOUND");
        assertError(call("checkSeatAvailability", "{\"showId\":" + showId + ",\"ticketCount\":6}"), "NOT_ENOUGH_SEATS");
        assertError(call("findTheatres", "{\"city\":\"Atlantis\"}"), "NO_THEATRES_FOUND");
        assertError(call("searchMovies", "{\"title\":\"zzqqxx\"}"), "NO_MOVIES_FOUND");
        assertError(call("searchMovies", "{\"genre\":\"Opera\"}"), "INVALID_ARGUMENTS");

        assertThat(turn.trace()).hasSize(11).allMatch(t -> t.status().equals("error"));
    }

    @Test
    void pastDatesAndStartedShowsAreRejected() throws Exception {
        assertError(call("findShowtimes", "{\"city\":\"Chennai\",\"date\":\"2020-01-01\"}"), "INVALID_ARGUMENTS");
        AgentTurn later = new AgentTurn(alice, new ConversationState(), ist(tomorrow(), 19, 5).toInstant());
        JsonNode started = json.readTree(callback("checkSeatAvailability")
                .call("{\"showId\":" + showId + "}", new ToolContext(later.asToolContext())));
        assertError(started, "SHOW_NOT_BOOKABLE");
    }

    private void assertError(JsonNode reply, String code) {
        assertThat(reply.at("/status").asText()).as(reply.toString()).isEqualTo("error");
        assertThat(reply.at("/errorCode").asText()).as(reply.toString()).isEqualTo(code);
        assertThat(reply.at("/summary").asText()).doesNotContain("Exception", "SQL", "org.");
        assertThat(reply.has("ok")).isFalse();
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return json.readTree(callback(tool).call(arguments, new ToolContext(turn.asToolContext())));
    }

    private ToolCallback callback(String tool) {
        return toolbox.callbacks().stream().filter(c -> c.getToolDefinition().name().equals(tool)).findFirst()
                .orElseThrow();
    }

    private java.util.List<String> required(String tool) throws Exception {
        JsonNode req = json.readTree(callback(tool).getToolDefinition().inputSchema()).path("required");
        java.util.List<String> names = new java.util.ArrayList<>();
        req.forEach(n -> names.add(n.asText()));
        return names;
    }
}
