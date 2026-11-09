package com.cinebot.booking;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle (docs/01-design.md §5). Allowed transitions are data, not scattered if-statements:
 * every state change goes through {@link Booking#transitionTo}, which rejects anything not listed here.
 */
public enum BookingStatus {
    PENDING,
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    private static final Map<BookingStatus, EnumSet<BookingStatus>> NEXT = new EnumMap<>(BookingStatus.class);

    static {
        NEXT.put(PENDING, EnumSet.of(PAYMENT_PENDING, CANCELLED, EXPIRED));
        NEXT.put(PAYMENT_PENDING, EnumSet.of(CONFIRMED, REFUND_PENDING, PAYMENT_FAILED, EXPIRED, CANCELLED));
        NEXT.put(CONFIRMED, EnumSet.of(REFUND_PENDING));
        // A capture that arrives after the booking died is refunded, never silently kept.
        NEXT.put(PAYMENT_FAILED, EnumSet.of(REFUND_PENDING));
        NEXT.put(EXPIRED, EnumSet.of(REFUND_PENDING));
        NEXT.put(CANCELLED, EnumSet.of(REFUND_PENDING));
        NEXT.put(REFUND_PENDING, EnumSet.of(REFUNDED));
        NEXT.put(REFUNDED, EnumSet.noneOf(BookingStatus.class));
    }

    public boolean canTransitionTo(BookingStatus next) {
        return NEXT.get(this).contains(next);
    }

    public Set<BookingStatus> allowedNext() {
        return EnumSet.copyOf(NEXT.get(this)); // EnumSet.copyOf(EnumSet) is fine when empty
    }

    /** Still holding seats and waiting for payment. */
    public boolean isOpen() {
        return this == PENDING || this == PAYMENT_PENDING;
    }
}
