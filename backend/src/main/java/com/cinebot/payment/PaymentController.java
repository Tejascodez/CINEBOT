package com.cinebot.payment;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.payment.dto.CreatePaymentOrderRequest;
import com.cinebot.payment.dto.PaymentOrderResponse;
import com.cinebot.payment.dto.VerifyPaymentRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentOrderService orders;
    private final PaymentVerificationService verification;

    public PaymentController(PaymentOrderService orders, PaymentVerificationService verification) {
        this.orders = orders;
        this.verification = verification;
    }

    /** Idempotent: repeated calls for the same booking return the same order (always 200). */
    @PostMapping("/orders")
    public PaymentOrderResponse createOrder(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody CreatePaymentOrderRequest request) {
        return orders.createOrder(user, request.bookingId());
    }

    @PostMapping("/verify")
    public BookingResponse verify(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody VerifyPaymentRequest request) {
        return verification.verify(user, request);
    }
}
