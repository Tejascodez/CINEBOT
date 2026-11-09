package com.cinebot.payment;

import com.cinebot.payment.gateway.GatewayPayment;
import com.cinebot.payment.gateway.GatewayRefund;
import com.cinebot.payment.gateway.PaymentGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Converges our state with the provider's, for everything the request path and webhooks may have missed
 * (crash mid-confirmation, lost webhooks, failed refund calls). The provider is the source of truth for
 * money movement; PostgreSQL is the source of truth for seats. All actions go through the same idempotent
 * settlement methods, so running it concurrently on several instances, or repeatedly, is safe.
 */
@Service
public class PaymentReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);

    public record Report(int confirmed, int failed, int expired, int refundsStarted, int refundsCompleted) {
    }

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentSettlementService settlement;
    private final RefundService refunds;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentReconciliation(PaymentRepository payments, PaymentGateway gateway, PaymentSettlementService settlement,
                                 RefundService refunds, PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        this.settlement = settlement;
        this.refunds = refunds;
        this.properties = properties;
        this.clock = clock;
    }

    public Report runOnce() {
        Instant now = clock.instant();
        Limit limit = Limit.of(properties.reconciler().batchSize());
        int confirmed = 0;
        int failed = 0;
        int expired = 0;
        int refundsStarted = 0;
        int refundsCompleted = 0;

        for (Payment p : payments.findByStatusAndCreatedAtBeforeOrderByCreatedAt(PaymentStatus.CREATED,
                now.minus(properties.reconciler().stuckAfter()), limit)) {
            try {
                List<GatewayPayment> attempts = gateway.fetchPaymentsForOrder(p.getProviderOrderId());
                Optional<GatewayPayment> captured = attempts.stream().filter(GatewayPayment::captured).findFirst();
                if (captured.isPresent()) {
                    settlement.applyCapture(p.getProviderOrderId(), captured.get().paymentId(), captured.get().amountPaise());
                    confirmed++;
                } else if (!attempts.isEmpty()) {
                    settlement.applyFailure(p.getProviderOrderId(), attempts.getLast().failureReason());
                    failed++;
                } else if (p.getOrderExpiresAt().isBefore(now)) {
                    settlement.markOrderExpired(p.getProviderOrderId());
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconciling order {} failed: {}", p.getProviderOrderId(), e.getMessage());
            }
        }

        for (Payment p : payments.findByStatusOrderByCreatedAt(PaymentStatus.REFUND_PENDING, limit)) {
            try {
                if (p.getProviderRefundId() == null) {
                    refunds.execute(p.getId());
                    refundsStarted++;
                } else if (gateway.fetchRefund(p.getProviderRefundId())
                        .filter(r -> r.status() == GatewayRefund.Status.PROCESSED).isPresent()) {
                    settlement.applyRefundProcessed(p.getProviderPaymentId(), p.getProviderRefundId());
                    refundsCompleted++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconciling refund for payment {} failed: {}", p.getId(), e.getMessage());
            }
        }
        Report report = new Report(confirmed, failed, expired, refundsStarted, refundsCompleted);
        if (confirmed + failed + expired + refundsStarted + refundsCompleted > 0) {
            log.info("Payment reconciliation: {}", report);
        }
        return report;
    }
}
