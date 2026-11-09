package com.cinebot.payment.gateway;

public record GatewayRefund(String refundId, String paymentId, long amountPaise, Status status) {

    public enum Status { PENDING, PROCESSED }
}
