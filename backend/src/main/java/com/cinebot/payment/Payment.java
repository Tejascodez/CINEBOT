package com.cinebot.payment;

import com.cinebot.payment.gateway.GatewayOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Our durable record of a provider order and everything that happened to it. */
@Entity
@Table(name = "payments")
public class Payment {

    private static final int MAX_REASON = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true, updatable = false)
    private Long bookingId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_order_id", nullable = false, unique = true, updatable = false)
    private String providerOrderId;

    @Column(name = "provider_payment_id", unique = true)
    private String providerPaymentId;

    @Column(name = "provider_refund_id", unique = true)
    private String providerRefundId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "order_expires_at", nullable = false, updatable = false)
    private Instant orderExpiresAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    static Payment created(Long bookingId, String provider, GatewayOrder order) {
        Payment p = new Payment();
        p.bookingId = bookingId;
        p.provider = provider;
        p.providerOrderId = order.orderId();
        p.amountPaise = order.amountPaise();
        p.currency = order.currency();
        p.orderExpiresAt = order.expiresAt();
        p.status = PaymentStatus.CREATED;
        return p;
    }

    void markCaptured(String paymentId, Instant now) {
        transition(PaymentStatus.CAPTURED);
        providerPaymentId = paymentId;
        capturedAt = now;
    }

    void markFailed(String reason) {
        transition(PaymentStatus.FAILED);
        failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), MAX_REASON));
    }

    void markExpired() {
        transition(PaymentStatus.EXPIRED);
    }

    void requestRefund(Instant now) {
        transition(PaymentStatus.REFUND_PENDING);
        refundRequestedAt = now;
    }

    void recordRefund(String refundId) {
        if (providerRefundId == null) {
            providerRefundId = refundId;
        }
    }

    void markRefunded(String refundId, Instant now) {
        recordRefund(refundId);
        transition(PaymentStatus.REFUNDED);
        refundedAt = now;
    }

    private void transition(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Payment " + id + " cannot move from " + status + " to " + next);
        }
        status = next;
    }

    boolean isCapturedAs(String paymentId) {
        return status.isMoneyReceived() && paymentId.equals(providerPaymentId);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getOrderExpiresAt() {
        return orderExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
