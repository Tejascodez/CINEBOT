package com.cinebot.payment;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Status of one provider order. FAILED/EXPIRED -> CAPTURED exists on purpose: a late capture after we gave up
 * is still money received, and must be recorded (then refunded), never dropped.
 */
public enum PaymentStatus {
    CREATED,
    CAPTURED,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED;

    private static final Map<PaymentStatus, EnumSet<PaymentStatus>> NEXT = new EnumMap<>(PaymentStatus.class);

    static {
        NEXT.put(CREATED, EnumSet.of(CAPTURED, FAILED, EXPIRED));
        NEXT.put(FAILED, EnumSet.of(CAPTURED));
        NEXT.put(EXPIRED, EnumSet.of(CAPTURED));
        NEXT.put(CAPTURED, EnumSet.of(REFUND_PENDING));
        NEXT.put(REFUND_PENDING, EnumSet.of(REFUNDED));
        NEXT.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** Money has been received for this order. */
    public boolean isMoneyReceived() {
        return this == CAPTURED || this == REFUND_PENDING || this == REFUNDED;
    }
}
