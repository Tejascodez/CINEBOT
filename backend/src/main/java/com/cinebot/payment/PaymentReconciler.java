package com.cinebot.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinebot.payment.reconciler.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconciler {

    private final PaymentReconciliation reconciliation;

    PaymentReconciler(PaymentReconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${cinebot.payment.reconciler.interval:PT1M}")
    void run() {
        reconciliation.runOnce();
    }
}
