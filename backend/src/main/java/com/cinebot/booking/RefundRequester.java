package com.cinebot.booking;

/**
 * Port owned by the booking module, implemented by the payment module (which depends on booking).
 * Lets BookingService cancel a CONFIRMED booking without importing payment code: no package cycle.
 */
public interface RefundRequester {

    /** Called inside the cancel transaction, after the booking moved to REFUND_PENDING. */
    void requestRefund(Long bookingId);
}
