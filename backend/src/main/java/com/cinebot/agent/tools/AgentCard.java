package com.cinebot.agent.tools;

/**
 * A UI payload rendered by the chat widget next to the agent's text (movie list, seat map, confirmation...).
 * Cards go to the frontend only; the model sees the compact {@link ToolReply} instead.
 */
public record AgentCard(Type type, Object data) {

    public enum Type {
        MOVIES, MOVIE_DETAILS, THEATRES, SHOWS, SEAT_MAP,
        HOLD_CONFIRMATION_REQUIRED, CANCEL_CONFIRMATION_REQUIRED,
        BOOKING_SUMMARY, PAYMENT_CONFIRMATION_REQUIRED, TICKET, BOOKINGS
    }
}
