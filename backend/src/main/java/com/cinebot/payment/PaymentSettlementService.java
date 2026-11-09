package com.cinebot.payment;

import com.cinebot.booking.BookingSettlement;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies provider facts (captured / failed / refunded) to our state. The single entry point used by the
 * verify endpoint, the webhook, and the reconciler, so all three behave identically. Every method is
 * idempotent: applying the same fact twice is a no-op.
 *
 * <p>Capture + confirmation happen in ONE transaction. If the process dies half-way, nothing is committed;
 * the provider still holds the capture, so the next webhook retry or reconciler run applies it again.
 * Business rejections (unknown order, amount mismatch) are thrown before any write.
 */
@Service
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    private final PaymentRepository payments;
    private final BookingSettlement bookings;
    private final RefundService refunds;
    private final PaymentFaults faults;
    private final Clock clock;

    public PaymentSettlementService(PaymentRepository payments, BookingSettlement bookings, RefundService refunds,
                                    PaymentFaults faults, Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.refunds = refunds;
        this.faults = faults;
        this.clock = clock;
    }

    /** @return the booking id */
    @Transactional
    public Long applyCapture(String orderId, String paymentId, long amountPaise) {
        Long bookingId = payments.findBookingIdByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Unknown order " + orderId));
        var locked = bookings.lock(bookingId);                         // seat_lock -> booking
        Payment payment = payments.findByBookingIdForUpdate(bookingId).orElseThrow(); // -> payment
        if (payment.isCapturedAs(paymentId)) {
            return bookingId; // duplicate delivery of the same fact
        }
        if (payment.getStatus().isMoneyReceived()) {
            log.error("Order {} captured twice ({} and {}): needs manual refund", orderId,
                    payment.getProviderPaymentId(), paymentId);
            throw new ApiException(ErrorCode.PAYMENT_CONFLICT);
        }
        if (amountPaise != payment.getAmountPaise()) {
            log.error("Order {} captured {} but expected {}", orderId, amountPaise, payment.getAmountPaise());
            throw new ApiException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        Instant now = clock.instant();
        payment.markCaptured(paymentId, now);
        faults.afterCaptureRecorded();

        boolean seatsSecured = bookings.confirmPaid(locked, now);
        if (!seatsSecured) {
            payment.requestRefund(now);
            refunds.executeAfterCommit(payment.getId());
        }
        log.info("Order {} captured: booking {} {}", orderId, locked.booking().getBookingReference(),
                locked.booking().getStatus());
        return bookingId;
    }

    @Transactional
    public void applyFailure(String orderId, String reason) {
        Long bookingId = payments.findBookingIdByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Unknown order " + orderId));
        var locked = bookings.lock(bookingId);
        Payment payment = payments.findByBookingIdForUpdate(bookingId).orElseThrow();
        if (payment.getStatus() != PaymentStatus.CREATED) {
            return; // already captured (a failed retry after success), failed or expired
        }
        payment.markFailed(reason);
        bookings.paymentFailed(locked, clock.instant()); // seats released immediately
    }

    /** No attempt was ever made and the order window closed. The booking itself is expired by the sweeper. */
    @Transactional
    public void markOrderExpired(String orderId) {
        payments.findBookingIdByOrderId(orderId).ifPresent(bookingId -> {
            bookings.lock(bookingId);
            Payment payment = payments.findByBookingIdForUpdate(bookingId).orElseThrow();
            if (payment.getStatus() == PaymentStatus.CREATED) {
                payment.markExpired();
            }
        });
    }

    /** Looked up by payment id: the refund webhook can arrive before we stored the refund id. */
    @Transactional
    public void applyRefundProcessed(String paymentId, String refundId) {
        Long bookingId = payments.findBookingIdByPaymentId(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Unknown payment " + paymentId));
        var locked = bookings.lock(bookingId);
        Payment payment = payments.findByBookingIdForUpdate(bookingId).orElseThrow();
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            return;
        }
        Instant now = clock.instant();
        payment.markRefunded(refundId, now);
        bookings.refunded(locked, now);
    }
}
