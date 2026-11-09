package com.cinebot.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dev demo of crash recovery: arm the fault, pay, watch the reconciler confirm the booking a minute later. */
@RestController
@RequestMapping("/api/v1/admin/payment-faults")
@ConditionalOnProperty(name = "cinebot.payment.faults.enabled", havingValue = "true")
public class AdminPaymentFaultsController {

    private final PaymentFaults faults;

    public AdminPaymentFaultsController(PaymentFaults faults) {
        this.faults = faults;
    }

    public record Faults(boolean crashAfterCapture) {
    }

    @GetMapping
    public Faults get() {
        return new Faults(faults.isCrashAfterCapture());
    }

    @PostMapping
    public Faults set(@RequestBody Faults request) {
        faults.setCrashAfterCapture(request.crashAfterCapture());
        return get();
    }
}
