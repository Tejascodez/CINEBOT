package com.cinebot.payment.gateway;

/** The provider could not be reached or rejected the call. Retryable from the caller's point of view. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
