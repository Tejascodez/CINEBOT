package com.cinebot.outbox;

import java.util.Set;

/**
 * A consumer of outbox events. Called inside a transaction that also records (handler, eventId) as processed,
 * so the handler's database effects happen exactly once even if the event is delivered again.
 * Throwing = "not handled": the transaction rolls back and the event is retried later with backoff.
 *
 * <p>Handlers must not depend on event order across aggregates or within one; design effects to be
 * commutative (counters) or self-describing (notifications carry the state they announce).
 */
public interface EventHandler {

    /** Stable name: it is the de-duplication key in processed_events. Never rename a deployed handler. */
    String name();

    Set<String> eventTypes();

    void handle(OutboxMessage message);
}
