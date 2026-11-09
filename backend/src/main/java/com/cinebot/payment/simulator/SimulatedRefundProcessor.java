package com.cinebot.payment.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dev only: the simulated provider settles pending refunds a few seconds after they were requested. */
@Component
@ConditionalOnProperty(name = "cinebot.payment.simulator.webhook-delivery", havingValue = "HTTP")
class SimulatedRefundProcessor {

    private final SimulatedPaymentGateway gateway;

    SimulatedRefundProcessor(SimulatedPaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "PT2S")
    void process() {
        gateway.processDueRefunds();
    }
}
