package com.cinebot.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinebot.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private final OutboxProcessor processor;
    private final OutboxProperties properties;

    OutboxRelay(OutboxProcessor processor, OutboxProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cinebot.outbox.relay.interval:PT1S}")
    void relay() {
        int taken;
        do {
            taken = processor.processBatch();
        } while (taken >= properties.batchSize()); // drain a backlog without waiting a full interval each time
    }
}
