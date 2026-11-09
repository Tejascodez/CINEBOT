package com.cinebot.booking;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Event contract for booking lifecycle changes (outbox payload). Self-describing: it carries the state it
 * announces, so consumers never need to read the booking back (which could already have moved on).
 */
public record BookingEvent(
        Long bookingId,
        String reference,
        Long userId,
        Long showId,
        BookingStatus fromStatus,
        BookingStatus toStatus,
        long totalPaise,
        List<String> seats,
        Instant occurredAt) {

    public static final String AGGREGATE = "booking";

    public static final String CREATED = "booking.created";
    public static final String PAYMENT_STARTED = "booking.payment_started";
    public static final String CONFIRMED = "booking.confirmed";
    public static final String PAYMENT_FAILED = "booking.payment_failed";
    public static final String EXPIRED = "booking.expired";
    public static final String CANCELLED = "booking.cancelled";
    public static final String REFUND_REQUESTED = "booking.refund_requested";
    public static final String REFUNDED = "booking.refunded";

    private static final Map<BookingStatus, String> TYPE_BY_STATUS = Map.of(
            BookingStatus.PAYMENT_PENDING, PAYMENT_STARTED,
            BookingStatus.CONFIRMED, CONFIRMED,
            BookingStatus.PAYMENT_FAILED, PAYMENT_FAILED,
            BookingStatus.EXPIRED, EXPIRED,
            BookingStatus.CANCELLED, CANCELLED,
            BookingStatus.REFUND_PENDING, REFUND_REQUESTED,
            BookingStatus.REFUNDED, REFUNDED);

    static String typeFor(BookingStatus reached) {
        return TYPE_BY_STATUS.get(reached);
    }
}
