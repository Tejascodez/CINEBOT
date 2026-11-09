package com.cinebot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.cinebot.agent.config.AgentToolsConfig.AgentToolbox;
import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.support.PaymentTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The booking tools and the confirmation gate, called exactly as the model calls them. Each {@link Chat} turn
 * = one user message; the gate only lets a confirm through in a LATER turn than its prepare.
 * Fixture show: tomorrow 18:00, A1-A3 REGULAR ₹200, B1-B2 PREMIUM ₹350.
 */
class BookingToolsIT extends PaymentTestSupport {

    @Autowired
    AgentToolbox toolbox;
    @Autowired
    ConfirmationGate gate;

    /** A conversation: persistent state, a new turn per user message. */
    final class Chat {
        final AuthenticatedUser user;
        final ConversationState state = new ConversationState();
        AgentTurn turn;

        Chat(AuthenticatedUser user) {
            this.user = user;
            userSays(Instant.now());
        }

        Chat userSays(Instant at) {
            state.setTurn(state.getTurn() + 1);
            turn = new AgentTurn(user, state, at);
            return this;
        }

        Chat userSays() {
            return userSays(Instant.now());
        }

        JsonNode call(String tool, String args) throws Exception {
            var callback = toolbox.callbacks().stream().filter(c -> c.getToolDefinition().name().equals(tool))
                    .findFirst().orElseThrow();
            return json.readTree(callback.call(args, new ToolContext(turn.asToolContext())));
        }
    }

    @Test
    void holdNeedsConfirmationInALaterTurnThenPaymentIsOnlyAButton() throws Exception {
        Chat chat = new Chat(alice);
        JsonNode prepared = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"a1\",\"A2\"]}");
        assertThat(prepared.at("/status").asText()).isEqualTo("ok");
        assertThat(prepared.at("/data/movie").asText()).isEqualTo("Interstellar");
        assertThat(prepared.at("/data/total").asText()).isEqualTo("₹400");
        assertThat(prepared.at("/data/seats/0/seat").asText()).isEqualTo("A1");
        assertThat(prepared.at("/data/showTime").asText()).endsWith("6:00 PM");
        String confirmationId = prepared.at("/data/confirmationId").asText();
        assertThat(seatStatus("A1")).as("prepare holds nothing").isEqualTo("AVAILABLE");
        assertThat(chat.turn.cards()).extracting(AgentCard::type)
                .containsExactly(AgentCard.Type.HOLD_CONFIRMATION_REQUIRED);

        // the model tries to confirm in the same reply, before the user has answered
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + confirmationId + "\"}"),
                "USER_CONFIRMATION_REQUIRED");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");

        // the user replies "yes"
        chat.userSays();
        JsonNode held = chat.call("confirmSeatHold", "{\"confirmationId\":\"" + confirmationId + "\"}");
        assertThat(held.at("/status").asText()).as(held.toString()).isEqualTo("ok");
        assertThat(held.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(held.at("/summary").asText()).contains("HELD (not paid)");
        assertThat(seatStatus("A1")).isEqualTo("LOCKED");
        long bookingId = held.at("/data/bookingId").asLong();
        assertThat(chat.state.getBookingId()).isEqualTo(bookingId);
        assertThat(chat.state.getPending()).isNull();

        // a repeated "yes" (or a retried request) does not create a second booking
        chat.userSays();
        JsonNode again = chat.call("confirmSeatHold", "{\"confirmationId\":\"" + confirmationId + "\"}");
        assertThat(again.at("/summary").asText()).contains("already held", "no new action");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);

        // payment: a button only, nothing charged by the agent
        JsonNode pay = chat.call("requestPayment", "{}");
        assertThat(pay.at("/summary").asText()).contains("NOTHING has been charged");
        assertThat(chat.turn.cards()).extracting(AgentCard::type)
                .contains(AgentCard.Type.PAYMENT_CONFIRMATION_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();

        // the human clicks Confirm & Pay (the normal payment API), then asks the agent
        pay(paymentOrders.createOrder(alice, bookingId));
        deliverWebhooks();
        chat.userSays();
        JsonNode details = chat.call("getBookingDetails", "{}");
        assertThat(details.at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(details.toString()).as("the QR payload is for the UI, not the model").doesNotContain("CBT1.");
        assertThat(chat.turn.cards()).extracting(AgentCard::type).containsExactly(AgentCard.Type.TICKET);
        assertThat(chat.state.getBookingStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void seatsTakenBetweenPrepareAndConfirmAreRecheckedAndNothingIsBooked() throws Exception {
        Chat chat = new Chat(alice);
        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\",\"A2\"]}")
                .at("/data/confirmationId").asText();
        book(bob, "A2"); // someone else is faster

        chat.userSays();
        JsonNode result = chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}");
        assertError(result, "SEATS_UNAVAILABLE");
        assertThat(result.at("/summary").asText()).contains("A2 was just taken", "nothing was held");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id = ?", Integer.class, alice.id()))
                .isZero();
        // the confirmation is used up: the model has to prepare fresh seats with the user
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "NO_PENDING_CONFIRMATION");
    }

    @Test
    void aFailedConfirmationIsNeverReportedAsDoneOnARepeatedYes() throws Exception {
        Chat chat = new Chat(alice);
        long earlier = holdInChat(chat, otherShow(), "A1"); // the conversation already has a booking

        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\",\"A2\"]}")
                .at("/data/confirmationId").asText();
        book(bob, "A2");
        chat.userSays();
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "SEATS_UNAVAILABLE");

        // "yes" again: nothing was held, so it must not answer "already held" with the earlier booking
        chat.userSays();
        JsonNode again = chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}");
        assertError(again, "NO_PENDING_CONFIRMATION");
        assertThat(chat.state.getBookingId()).isEqualTo(earlier);
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
    }

    @Test
    void aRepeatedCancellationReportsTheBookingItCancelledNotTheConversationsCurrentOne() throws Exception {
        Chat chat = new Chat(alice);
        long current = holdInChat(chat, show, "A1");
        BookingResponse other = bookOn(alice, otherShow(), "A2");

        String id = chat.call("prepareCancellation", "{\"bookingId\":" + other.id() + "}")
                .at("/data/confirmationId").asText();
        chat.userSays();
        assertThat(chat.call("confirmCancellation", "{\"confirmationId\":\"" + id + "\"}").at("/data/status")
                .asText()).isEqualTo("CANCELLED");

        chat.userSays();
        JsonNode again = chat.call("confirmCancellation", "{\"confirmationId\":\"" + id + "\"}");
        assertThat(again.at("/summary").asText()).contains("already carried out", other.reference());
        assertThat(again.at("/data/bookingId").asLong()).isEqualTo(other.id());
        assertThat(bookingStatus(current)).as("the conversation's own hold is untouched").isEqualTo("PENDING");
    }

    @Test
    void aDeclinedConfirmationCannotBeConfirmed() throws Exception {
        Chat chat = new Chat(alice);
        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .at("/data/confirmationId").asText();

        chat.userSays();
        assertThat(gate.withdrawIfDeclined(chat.state, "No, don't hold them", null)).isTrue();
        assertThat(chat.state.getPending()).isNull();
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "USER_DECLINED");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");

        // the card's button names the exact action; a stale id declines nothing
        id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .at("/data/confirmationId").asText();
        chat.userSays();
        assertThat(gate.withdrawIfDeclined(chat.state, "Not now", "ZZZZZZZZ")).isFalse();
        assertThat(gate.withdrawIfDeclined(chat.state, "Hmm", id.toLowerCase())).isTrue();
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "USER_DECLINED");
    }

    @Test
    void prepareValidatesSeatsAgainstTheLiveSeatMap() throws Exception {
        book(bob, "B1");
        Chat chat = new Chat(alice);
        assertError(chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"B1\",\"B2\"]}"),
                "SEATS_UNAVAILABLE");
        assertError(chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"Z9\"]}"), "UNKNOWN_SEATS");
        assertError(chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"front row\"]}"),
                "INVALID_ARGUMENTS");
        assertError(chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[]}"), "INVALID_ARGUMENTS");
        assertError(chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\",\"A2\",\"A3\",\"B2\","
                + "\"C1\",\"C2\",\"C3\",\"C4\",\"C5\",\"C6\",\"C7\"]}"), "INVALID_ARGUMENTS");
        assertThat(chat.state.getPending()).isNull();
    }

    @Test
    void wrongExpiredOrForeignConfirmationsAreRejected() throws Exception {
        Chat chat = new Chat(alice);
        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .at("/data/confirmationId").asText();

        chat.userSays();
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"WRONG123\"}"), "NO_PENDING_CONFIRMATION");
        assertError(chat.call("confirmCancellation", "{\"confirmationId\":\"" + id + "\"}"), "NO_PENDING_CONFIRMATION");
        assertError(chat.call("confirmSeatHold", "{}"), "INVALID_ARGUMENTS");

        Chat bobsChat = new Chat(bob).userSays();
        assertError(bobsChat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "NO_PENDING_CONFIRMATION");

        chat.userSays(Instant.now().plus(Duration.ofMinutes(11))); // confirmation TTL is 10 minutes
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "CONFIRMATION_EXPIRED");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
    }

    @Test
    void switchingToAnotherShowVoidsAPreparedHold() throws Exception {
        long otherShow = show(jdbc.queryForObject("SELECT movie_id FROM shows WHERE id = ?", Long.class, show),
                jdbc.queryForObject("SELECT screen_id FROM shows WHERE id = ?", Long.class, show),
                ist(tomorrow(), 22, 0)).id();
        Chat chat = new Chat(alice);
        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .at("/data/confirmationId").asText();
        chat.userSays();
        chat.call("checkSeatAvailability", "{\"showId\":" + otherShow + "}");
        assertError(chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}"), "NO_PENDING_CONFIRMATION");
    }

    @Test
    void oneOpenHoldPerShow() throws Exception {
        Chat chat = new Chat(alice);
        String id = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A1\"]}")
                .at("/data/confirmationId").asText();
        chat.userSays();
        chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}");

        JsonNode second = chat.call("prepareSeatHold", "{\"showId\":" + show + ",\"seats\":[\"A3\"]}");
        assertError(second, "ACTIVE_LOCK_EXISTS");
        assertThat(second.at("/summary").asText()).contains("already holds seats A1");
    }

    @Test
    void cancellingAHoldNeedsConfirmationAndReleasesTheSeats() throws Exception {
        long bookingId = book(alice, "A1", "A2").id();
        Chat chat = new Chat(alice);
        JsonNode prepared = chat.call("prepareCancellation", "{\"bookingId\":" + bookingId + "}");
        assertThat(prepared.at("/data/consequence").asText()).contains("Nothing was charged");
        String id = prepared.at("/data/confirmationId").asText();

        assertError(chat.call("confirmCancellation", "{\"confirmationId\":\"" + id + "\"}"),
                "USER_CONFIRMATION_REQUIRED");
        assertThat(bookingStatus(bookingId)).isEqualTo("PENDING");

        chat.userSays();
        JsonNode cancelled = chat.call("confirmCancellation", "{\"confirmationId\":\"" + id + "\"}");
        assertThat(cancelled.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertError(chat.call("prepareCancellation", "{\"bookingId\":" + bookingId + "}"), "NOT_CANCELLABLE");
    }

    @Test
    void cancellingAPaidBookingRefundsItButNotCloseToShowtime() throws Exception {
        var booking = book(alice, "B1");
        pay(order(alice, booking));
        deliverWebhooks();

        Chat late = new Chat(alice).userSays(ist(tomorrow(), 17, 0).toInstant()); // 1 h before an 18:00 show
        assertError(late.call("prepareCancellation", "{\"bookingId\":" + booking.id() + "}"),
                "CANCELLATION_WINDOW_CLOSED");

        Chat chat = new Chat(alice);
        JsonNode prepared = chat.call("prepareCancellation", "{\"bookingId\":" + booking.id() + "}");
        assertThat(prepared.at("/data/consequence").asText()).contains("₹350 is refunded");
        chat.userSays();
        JsonNode cancelled = chat.call("confirmCancellation",
                "{\"confirmationId\":\"" + prepared.at("/data/confirmationId").asText() + "\"}");
        assertThat(cancelled.at("/data/status").asText()).isIn("REFUND_PENDING", "REFUNDED");
        assertThat(cancelled.at("/summary").asText()).contains("cancelled");
    }

    @Test
    void bookingsArePrivate() throws Exception {
        long alicesBooking = book(alice, "A1").id();
        Chat bobsChat = new Chat(bob);
        assertError(bobsChat.call("getBookingDetails", "{\"bookingId\":" + alicesBooking + "}"), "BOOKING_NOT_FOUND");
        assertError(bobsChat.call("prepareCancellation", "{\"bookingId\":" + alicesBooking + "}"), "BOOKING_NOT_FOUND");
        assertError(bobsChat.call("requestPayment", "{\"bookingId\":" + alicesBooking + "}"), "BOOKING_NOT_FOUND");
        assertError(bobsChat.call("listMyBookings", "{}"), "NO_BOOKINGS_FOUND");
        assertError(bobsChat.call("getBookingDetails", "{}"), "INVALID_ARGUMENTS"); // no booking in this chat

        Chat alicesChat = new Chat(alice);
        JsonNode mine = alicesChat.call("listMyBookings", "{}");
        assertThat(mine.at("/data/bookings/0/bookingId").asLong()).isEqualTo(alicesBooking);
        assertThat(mine.at("/data/bookings/0/total").asText()).isEqualTo("₹200");
    }

    /** Prepare + confirm through the tools, as two user turns; returns the booking id. */
    private long holdInChat(Chat chat, long showId, String seat) throws Exception {
        String id = chat.call("prepareSeatHold", "{\"showId\":" + showId + ",\"seats\":[\"" + seat + "\"]}")
                .at("/data/confirmationId").asText();
        chat.userSays();
        JsonNode held = chat.call("confirmSeatHold", "{\"confirmationId\":\"" + id + "\"}");
        assertThat(held.at("/status").asText()).as(held.toString()).isEqualTo("ok");
        chat.userSays();
        return held.at("/data/bookingId").asLong();
    }

    /** Another show of the fixture movie on the fixture screen, tomorrow 22:00. */
    private long otherShow() {
        return show(jdbc.queryForObject("SELECT movie_id FROM shows WHERE id = ?", Long.class, show),
                jdbc.queryForObject("SELECT screen_id FROM shows WHERE id = ?", Long.class, show),
                ist(tomorrow(), 22, 0)).id();
    }

    private BookingResponse bookOn(AuthenticatedUser user, long showId, String... labels) {
        List<Long> ids = Arrays.stream(labels).map(l -> seat(showId, l)).toList();
        return bookingService.create(user, seatLocks.lock(user, new LockSeatsRequest(showId, ids)).lockId()).booking();
    }

    private void assertError(JsonNode reply, String code) {
        assertThat(reply.at("/status").asText()).as(reply.toString()).isEqualTo("error");
        assertThat(reply.at("/errorCode").asText()).as(reply.toString()).isEqualTo(code);
    }
}
