package com.cinebot.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay: delivers outbox events to in-process handlers.
 * <ol>
 *   <li><b>Lease</b> a batch: one statement marks due PENDING rows locked_until = now + lease, using
 *       FOR UPDATE SKIP LOCKED, so several instances never take the same events. No transaction stays open
 *       while handlers run; if this worker dies, the lease simply expires and another worker retries.</li>
 *   <li>For each event and each subscribed handler: in its own transaction, insert processed_events
 *       (PK conflict = already done, skip) and run the handler. One handler failing never re-runs the others.</li>
 *   <li>All handlers done -> DONE. Otherwise attempts+1 and available_at = now + exponential backoff;
 *       after maxAttempts -> DEAD (the dead-letter state; replay via the admin endpoint).</li>
 * </ol>
 * Delivery is at-least-once; with processed_events, each handler's DB effect is exactly-once.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final List<EventHandler> handlers;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxProcessor(List<EventHandler> handlers, NamedParameterJdbcTemplate jdbc,
                           PlatformTransactionManager txManager, OutboxProperties properties, Clock clock) {
        this.handlers = List.copyOf(handlers);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.properties = properties;
        this.clock = clock;
    }

    /** @return number of events taken from the outbox in this batch */
    public int processBatch() {
        List<OutboxMessage> batch = lease();
        for (OutboxMessage message : batch) {
            process(message);
        }
        return batch.size();
    }

    private List<OutboxMessage> lease() {
        Instant now = clock.instant();
        return jdbc.query("""
                UPDATE outbox_events SET locked_until = :leaseEnd
                WHERE id IN (SELECT id FROM outbox_events
                             WHERE status = 'PENDING' AND available_at <= :now
                               AND (locked_until IS NULL OR locked_until < :now)
                             ORDER BY available_at, created_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                RETURNING id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts, created_at
                """, new MapSqlParameterSource("now", Timestamp.from(now))
                        .addValue("leaseEnd", Timestamp.from(now.plus(properties.lease())))
                        .addValue("limit", properties.batchSize()),
                (rs, i) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant()))
                .stream().sorted(java.util.Comparator.comparing(OutboxMessage::createdAt)).toList();
    }

    private void process(OutboxMessage message) {
        List<String> failures = new ArrayList<>();
        for (EventHandler handler : handlers) {
            if (!handler.eventTypes().contains(message.type())) {
                continue;
            }
            try {
                tx.executeWithoutResult(s -> {
                    if (markProcessed(handler, message)) {
                        handler.handle(message);
                    }
                });
            } catch (RuntimeException e) {
                failures.add(handler.name() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("Handler {} failed on event {} ({}), attempt {}", handler.name(), message.id(),
                        message.type(), message.attempts() + 1, e);
            }
        }
        if (failures.isEmpty()) {
            jdbc.update("UPDATE outbox_events SET status = 'DONE', processed_at = :now, locked_until = NULL WHERE id = :id",
                    new MapSqlParameterSource("id", message.id()).addValue("now", Timestamp.from(clock.instant())));
        } else {
            scheduleRetry(message, String.join(" | ", failures));
        }
    }

    /** @return false if this handler already processed the event (redelivery) */
    private boolean markProcessed(EventHandler handler, OutboxMessage message) {
        return jdbc.update("""
                INSERT INTO processed_events (handler, event_id, processed_at) VALUES (:handler, :id, :now)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("handler", handler.name()).addValue("id", message.id())
                .addValue("now", Timestamp.from(clock.instant()))) == 1;
    }

    private void scheduleRetry(OutboxMessage message, String error) {
        int attempts = message.attempts() + 1;
        boolean dead = attempts >= properties.maxAttempts();
        Duration backoff = backoff(attempts);
        jdbc.update("""
                UPDATE outbox_events
                SET attempts = :attempts, status = :status, available_at = :availableAt, locked_until = NULL,
                    last_error = :error
                WHERE id = :id
                """, new MapSqlParameterSource("id", message.id()).addValue("attempts", attempts)
                .addValue("status", dead ? "DEAD" : "PENDING")
                .addValue("availableAt", Timestamp.from(clock.instant().plus(backoff)))
                .addValue("error", error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error));
        if (dead) {
            log.error("Outbox event {} ({}) moved to DEAD after {} attempts: {}", message.id(), message.type(), attempts, error);
        }
    }

    /** 2s, 4s, 8s ... capped. */
    Duration backoff(int attempts) {
        long seconds = (long) Math.min(Math.pow(2, attempts), properties.maxBackoff().toSeconds());
        return Duration.ofSeconds(seconds);
    }
}
