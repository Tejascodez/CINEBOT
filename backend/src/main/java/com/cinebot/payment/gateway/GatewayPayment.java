package com.cinebot.payment.gateway;

public record GatewayPayment(String paymentId, String orderId, long amountPaise, Status status, String failureReason) {

    public enum Status { CAPTURED, FAILED }

    public boolean captured() {
        return status == Status.CAPTURED;
    }
}
