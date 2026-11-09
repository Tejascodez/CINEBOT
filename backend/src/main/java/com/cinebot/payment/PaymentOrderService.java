package com.cinebot.payment;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.BookingSettlement;
import com.cinebot.booking.BookingStatus;
import com.cinebot.booking.lock.SeatLockStatus;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.payment.dto.PaymentOrderResponse;
import com.cinebot.payment.gateway.GatewayOrder;
import com.cinebot.payment.gateway.PaymentGateway;
import com.cinebot.payment.gateway.PaymentGatewayException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POST /payments/orders, in three phases so the provider call happens OUTSIDE any transaction:
 * <ol>
 *   <li>tx: lock rows, return the existing order if any (idempotent), else validate and read the amount</li>
 *   <li>no tx: create the provider order (amount always from our DB, never from the client)</li>
 *   <li>tx: lock rows again, re-validate, insert the payment, booking -> PAYMENT_PENDING</li>
 * </ol>
 * Two concurrent requests may both reach phase 2; UNIQUE(booking_id) + the row locks let only one persist.
 * The other provider order is orphaned and harmless: nobody is shown its checkout.
 */
@Service
public class PaymentOrderService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderService.class);
    /** Don't start a checkout the customer cannot finish before the seat hold ends. */
    static final Duration MIN_REMAINING_HOLD = Duration.ofSeconds(30);

    private final PaymentRepository payments;
    private final BookingSettlement bookings;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentOrderService(PaymentRepository payments, BookingSettlement bookings, PaymentGateway gateway,
                               TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.bookings = bookings;
        this.gateway = gateway;
        this.tx = tx;
        this.clock = clock;
    }

    private record Draft(PaymentOrderResponse existing, String receipt, long amountPaise, String currency,
                         Instant expiresAt) {
    }

    public PaymentOrderResponse createOrder(AuthenticatedUser user, Long bookingId) {
        if (!bookings.isOwnedBy(bookingId, user.id())) {
            throw new ApiException(ErrorCode.BOOKING_NOT_FOUND, "Booking " + bookingId + " not found");
        }
        Draft draft = tx.execute(s -> {
            var locked = bookings.lock(bookingId);
            var existing = payments.findByBookingId(bookingId);
            if (existing.isPresent()) {
                return new Draft(toResponse(existing.get()), null, 0, null, null);
            }
            requirePayable(locked, clock.instant());
            return new Draft(null, locked.booking().getBookingReference(), locked.booking().getTotalAmountPaise(),
                    locked.booking().getCurrency(), locked.booking().getExpiresAt());
        });
        if (draft.existing() != null) {
            return draft.existing();
        }

        GatewayOrder order;
        try {
            order = gateway.createOrder(draft.receipt(), draft.amountPaise(), draft.currency(), draft.expiresAt());
        } catch (PaymentGatewayException e) {
            throw new ApiException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }

        return tx.execute(s -> {
            var locked = bookings.lock(bookingId);
            var existing = payments.findByBookingId(bookingId);
            if (existing.isPresent()) {
                log.info("Concurrent order creation for booking {}: provider order {} orphaned", bookingId, order.orderId());
                return toResponse(existing.get());
            }
            bookings.startPayment(locked, clock.instant()); // re-validates under lock
            return toResponse(payments.saveAndFlush(Payment.created(bookingId, gateway.provider(), order)));
        });
    }

    private static void requirePayable(BookingSettlement.Locked locked, Instant now) {
        if (locked.booking().getStatus() != BookingStatus.PENDING) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PAYABLE, "Booking is " + locked.booking().getStatus());
        }
        if (locked.lock().getStatus() != SeatLockStatus.ACTIVE
                || !locked.lock().getExpiresAt().isAfter(now.plus(MIN_REMAINING_HOLD))) {
            throw new ApiException(ErrorCode.LOCK_EXPIRED, "Your seat hold has expired or is about to. Please select seats again.");
        }
    }

    static PaymentOrderResponse toResponse(Payment p) {
        return new PaymentOrderResponse(p.getId(), p.getBookingId(), p.getProvider(), p.getProviderOrderId(),
                p.getAmountPaise(), p.getCurrency(), p.getStatus(), BusinessTime.toLocal(p.getOrderExpiresAt()),
                "/checkout/" + p.getProviderOrderId());
    }
}
