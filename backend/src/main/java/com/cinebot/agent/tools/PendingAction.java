package com.cinebot.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A sensitive action the agent has PREPARED but not executed: it waits for the user's explicit "yes".
 * Stored server-side, so the model can only confirm exactly what the user was shown (same seats, same price),
 * identified by an unguessable one-time {@code id}.
 *
 * @param createdTurn the user turn in which it was prepared; it can only be confirmed in a LATER turn,
 *                    i.e. after the user has read the summary and replied
 * @param expiresAt   ISO-8601 instant
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingAction(String id, Type type, int createdTurn, String expiresAt,
                            Long showId, List<Long> showSeatIds, List<String> seatLabels, Long totalPaise,
                            Long bookingId, String bookingReference) {

    public enum Type { HOLD_SEATS, CANCEL_BOOKING }

    String describe() {
        return switch (type) {
            case HOLD_SEATS -> "hold seats " + String.join(", ", seatLabels) + " for " + Display.rupees(totalPaise);
            case CANCEL_BOOKING -> "cancel booking " + bookingReference;
        };
    }
}
