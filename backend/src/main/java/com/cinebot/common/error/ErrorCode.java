package com.cinebot.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Clients (frontend, chatbot) switch on {@code code}, never on message text.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
    MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found"),
    MOVIE_RUNTIME_UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "Movie runtime is unknown"),
    SYNC_ALREADY_RUNNING(HttpStatus.CONFLICT, "A movie sync is already running"),
    THEATRE_NOT_FOUND(HttpStatus.NOT_FOUND, "Theatre not found"),
    THEATRE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A theatre with this name already exists in the city"),
    THEATRE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Theatre is inactive"),
    SCREEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Screen not found"),
    SCREEN_NAME_TAKEN(HttpStatus.CONFLICT, "Screen name already used in this theatre"),
    SCREEN_HAS_SHOWS(HttpStatus.CONFLICT, "Screen layout cannot change once shows exist"),
    SCREEN_HAS_NO_SEATS(HttpStatus.UNPROCESSABLE_ENTITY, "Screen has no seat layout"),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Show not found"),
    SHOW_OVERLAP(HttpStatus.CONFLICT, "Show overlaps another show on this screen"),
    SHOW_IN_PAST(HttpStatus.UNPROCESSABLE_ENTITY, "Show must start in the future"),
    SHOW_HAS_BOOKINGS(HttpStatus.CONFLICT, "Show has locked or booked seats"),
    INVALID_PRICING(HttpStatus.UNPROCESSABLE_ENTITY, "Prices missing for some seat types"),
    SHOW_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Show is not open for booking"),
    INVALID_SEAT_SELECTION(HttpStatus.UNPROCESSABLE_ENTITY, "Selected seats do not belong to this show"),
    SEATS_UNAVAILABLE(HttpStatus.CONFLICT, "Seats unavailable"),
    ACTIVE_LOCK_EXISTS(HttpStatus.CONFLICT, "You already hold seats for this show"),
    LOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat lock not found"),
    LOCK_EXPIRED(HttpStatus.CONFLICT, "Seat lock expired"),
    LOCK_NOT_ACTIVE(HttpStatus.CONFLICT, "Seat lock is no longer active"),
    LOCK_IN_USE(HttpStatus.CONFLICT, "Seat lock belongs to an open booking; cancel the booking instead"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid booking state transition"),
    CANCELLATION_REQUIRES_REFUND(HttpStatus.CONFLICT, "Confirmed bookings are cancelled through the refund flow"),
    CANCELLATION_WINDOW_CLOSED(HttpStatus.CONFLICT, "Too close to showtime to cancel"),
    BOOKING_NOT_PAYABLE(HttpStatus.CONFLICT, "Booking cannot be paid"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    INVALID_PAYMENT_SIGNATURE(HttpStatus.BAD_REQUEST, "Payment signature is invalid"),
    PAYMENT_NOT_CAPTURED(HttpStatus.UNPROCESSABLE_ENTITY, "Payment has not been captured"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Captured amount does not match the order"),
    PAYMENT_CONFLICT(HttpStatus.CONFLICT, "Order already captured by a different payment"),
    INVALID_WEBHOOK(HttpStatus.BAD_REQUEST, "Webhook rejected"),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Payment provider unavailable"),
    CHECKOUT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Checkout order not found"),
    CHECKOUT_ORDER_CLOSED(HttpStatus.CONFLICT, "Checkout order is already paid or expired"),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Conversation not found"),
    CONVERSATION_BUSY(HttpStatus.CONFLICT, "The previous message in this conversation is still being processed"),
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The AI assistant is not configured"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The AI assistant is temporarily unavailable"),
    TMDB_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "TMDB credentials are not configured"),
    TMDB_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "TMDB request failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "https://cinebot.dev/errors/" + name().toLowerCase().replace('_', '-');
    }
}
