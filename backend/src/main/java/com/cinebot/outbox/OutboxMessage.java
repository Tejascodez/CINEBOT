package com.cinebot.outbox;

import java.time.Instant;
import java.util.UUID;

/** An event as delivered to handlers. {@code payload} is JSON; handlers deserialise what they need. */
public record OutboxMessage(UUID id, String aggregateType, String aggregateId, String type, String payload,
                            int attempts, Instant createdAt) {
}
