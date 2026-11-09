package com.cinebot.payment;

import com.cinebot.booking.RefundRequester;
import com.cinebot.payment.gateway.GatewayRefund;
import com.cinebot.payment.gateway.PaymentGateway;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds follow "persist the intent, then act": the payment is marked REFUND_PENDING in the business
 * transaction; the provider is called only after commit (never inside a transaction). If that call fails or
 * the process dies, the reconciler finds REFUND_PENDING without a refund id and retries. The idempotency key
 * (one per payment) makes retries safe: the provider never refunds twice.
 */
@Service
public class RefundService implements RefundRequester {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(PaymentRepository payments, PaymentGateway gateway,
                         org.springframework.transaction.PlatformTransactionManager txManager, Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        // REQUIRES_NEW, because execute() also runs from afterCommit(): there the finished transaction's resources
        // are still bound, and a REQUIRED template would silently "join" it, so its writes would never commit.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Booking cancelled after payment: called inside the cancel transaction (rows already locked). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRefund(Long bookingId) {
        Payment payment = payments.findByBookingIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalStateException("Confirmed booking " + bookingId + " has no payment"));
        payment.requestRefund(clock.instant());
        executeAfterCommit(payment.getId());
    }

    void executeAfterCommit(Long paymentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    execute(paymentId);
                } catch (RuntimeException e) {
                    log.warn("Refund for payment {} not started yet ({}); reconciler will retry", paymentId, e.getMessage());
                }
            }
        });
    }

    /** Starts the provider refund if not yet started. Safe to call any number of times. */
    public void execute(Long paymentId) {
        Payment payment = tx.execute(s -> payments.findById(paymentId).orElseThrow());
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING || payment.getProviderRefundId() != null) {
            return;
        }
        GatewayRefund refund = gateway.refund(payment.getProviderPaymentId(), payment.getAmountPaise(),
                "refund-" + payment.getId());
        tx.executeWithoutResult(s -> payments.findById(paymentId).ifPresent(p -> p.recordRefund(refund.refundId())));
        log.info("Refund {} started for payment {}", refund.refundId(), paymentId);
    }
}
