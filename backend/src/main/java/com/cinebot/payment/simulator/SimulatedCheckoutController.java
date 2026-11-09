package com.cinebot.payment.simulator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated provider's "hosted checkout". The mock checkout page calls this with Pay or Fail; the
 * response is what a real checkout hands back to the browser (orderId, paymentId, signature), which the
 * frontend then sends to POST /api/v1/payments/verify. Not part of CineBot's own API surface.
 */
@RestController
@RequestMapping("/api/v1/payments/simulator")
@ConditionalOnProperty(name = "cinebot.payment.simulator.checkout-enabled", havingValue = "true")
public class SimulatedCheckoutController {

    private final SimulatedPaymentGateway gateway;

    public SimulatedCheckoutController(SimulatedPaymentGateway gateway) {
        this.gateway = gateway;
    }

    public record CheckoutRequest(@NotBlank @Size(max = 40) String orderId,
                                  @NotNull SimulatedPaymentGateway.Outcome outcome) {
    }

    @PostMapping("/checkout")
    public SimulatedPaymentGateway.CheckoutResult checkout(@Valid @RequestBody CheckoutRequest request) {
        return gateway.checkout(request.orderId(), request.outcome());
    }
}
