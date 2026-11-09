package com.cinebot.payment;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Fault injection for the failure scenario "payment succeeds, backend crashes before confirming".
 * Only armable when cinebot.payment.faults.enabled=true (dev/test).
 */
@Component
public class PaymentFaults {

    private final boolean enabled;
    private final AtomicBoolean crashAfterCapture = new AtomicBoolean();

    public PaymentFaults(PaymentProperties properties) {
        this.enabled = properties.faults().enabled();
    }

    public void setCrashAfterCapture(boolean value) {
        if (value && !enabled) {
            throw new IllegalStateException("Fault injection is disabled");
        }
        crashAfterCapture.set(value);
    }

    public boolean isCrashAfterCapture() {
        return crashAfterCapture.get();
    }

    /** Simulates the process dying mid-transaction: everything since the transaction began is rolled back. */
    void afterCaptureRecorded() {
        if (enabled && crashAfterCapture.get()) {
            throw new IllegalStateException("Injected fault: crash after capture, before confirmation");
        }
    }
}
