package com.cinebot.payment;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.BookingService;
import com.cinebot.booking.BookingSettlement;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.payment.dto.VerifyPaymentRequest;
import com.cinebot.payment.gateway.GatewayPayment;
import com.cinebot.payment.gateway.PaymentGateway;
import org.springframework.stereotype.Service;

/**
 * POST /payments/verify: the browser's "payment succeeded" callback. Never trusted on its own:
 * <ol>
 *   <li>the signature proves the ids came from the provider's checkout, not typed in by the user;</li>
 *   <li>the provider is then asked directly whether that payment is really CAPTURED, for how much, on which order.</li>
 * </ol>
 * The webhook delivers the same fact independently; whichever arrives first confirms, the other is a no-op.
 */
@Service
public class PaymentVerificationService {

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PaymentSettlementService settlement;
    private final BookingSettlement bookings;
    private final BookingService bookingService;

    public PaymentVerificationService(PaymentGateway gateway, PaymentRepository payments,
                                      PaymentSettlementService settlement, BookingSettlement bookings,
                                      BookingService bookingService) {
        this.gateway = gateway;
        this.payments = payments;
        this.settlement = settlement;
        this.bookings = bookings;
        this.bookingService = bookingService;
    }

    public BookingResponse verify(AuthenticatedUser user, VerifyPaymentRequest request) {
        if (!gateway.verifyCheckoutSignature(request.orderId(), request.paymentId(), request.signature())) {
            throw new ApiException(ErrorCode.INVALID_PAYMENT_SIGNATURE);
        }
        Long bookingId = payments.findBookingIdByOrderId(request.orderId())
                .filter(id -> bookings.isOwnedBy(id, user.id()))
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND));
        GatewayPayment captured = gateway.fetchPayment(request.paymentId())
                .filter(p -> p.orderId().equals(request.orderId()))
                .filter(GatewayPayment::captured)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_CAPTURED));
        settlement.applyCapture(captured.orderId(), captured.paymentId(), captured.amountPaise());
        return bookingService.get(user, bookingId);
    }
}
