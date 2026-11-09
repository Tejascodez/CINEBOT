package com.cinebot.payment.simulator;

import com.cinebot.common.crypto.Hmac;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cinebot.payment.webhook.WebhookEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * Delivers provider webhooks the way real providers do: asynchronously, after a delay, signed, retried with
 * backoff on failure, and (optionally) more than once. With delivery NONE (tests) events are queued so a test
 * can deliver exactly what the provider produced.
 */
@Component
public class SimulatedWebhookPublisher {

    public static final String SIGNATURE_HEADER = "X-Cinebot-Signature";
    private static final Logger log = LoggerFactory.getLogger(SimulatedWebhookPublisher.class);
    private static final int MAX_ATTEMPTS = 5;

    public record SignedWebhook(String body, String signature) {
    }

    private final SimulatorProperties properties;
    private final ObjectMapper json;
    private final TaskScheduler scheduler;
    private final RestClient http;
    private final ConcurrentLinkedQueue<SignedWebhook> undelivered = new ConcurrentLinkedQueue<>();

    public SimulatedWebhookPublisher(SimulatorProperties properties, ObjectMapper json, TaskScheduler scheduler,
                                     RestClient.Builder http) {
        this.properties = properties;
        this.json = json;
        this.scheduler = scheduler;
        this.http = http.build();
    }

    /** The provider only notifies about state it has committed. */
    void publishAfterCommit(WebhookEvent event) {
        SignedWebhook webhook = sign(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(webhook);
            }
        });
    }

    public SignedWebhook sign(WebhookEvent event) {
        try {
            String body = json.writeValueAsString(event);
            return new SignedWebhook(body, Hmac.sha256Hex(properties.webhookSecret(), body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test hook: webhooks produced but not delivered (delivery NONE). */
    public List<SignedWebhook> drain() {
        List<SignedWebhook> out = new ArrayList<>();
        for (SignedWebhook w; (w = undelivered.poll()) != null; ) {
            out.add(w);
        }
        return out;
    }

    private void deliver(SignedWebhook webhook) {
        if (properties.webhookDelivery() == SimulatorProperties.Delivery.NONE) {
            undelivered.add(webhook);
            return;
        }
        schedule(webhook, properties.webhookDelay(), 1);
        if (properties.duplicateWebhooks()) {
            schedule(webhook, properties.webhookDelay().plusSeconds(1), 1); // same event id, delivered twice
        }
    }

    private void schedule(SignedWebhook webhook, Duration delay, int attempt) {
        scheduler.schedule(() -> post(webhook, attempt), Instant.now().plus(delay));
    }

    private void post(SignedWebhook webhook, int attempt) {
        try {
            http.post().uri(properties.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, webhook.signature())
                    .body(webhook.body())
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            if (attempt >= MAX_ATTEMPTS) {
                log.error("Simulated webhook dropped after {} attempts: {}", attempt, e.getMessage());
                return;
            }
            Duration backoff = Duration.ofSeconds((long) Math.pow(2, attempt)); // 2, 4, 8, 16 s
            log.warn("Simulated webhook delivery failed (attempt {}), retrying in {}s", attempt, backoff.toSeconds());
            schedule(webhook, backoff, attempt + 1);
        }
    }
}
