package com.cinebot.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the outbox. MANDATORY: an event must be part of the business transaction it describes;
 * calling this outside one would recreate the dual-write problem the outbox exists to solve.
 */
@Component
public class OutboxWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, Object aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        try {
            jdbc.update("""
                    INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, available_at, created_at)
                    VALUES (:id, :aggType, :aggId, :type, CAST(:payload AS jsonb), :now, :now)
                    """, new MapSqlParameterSource("id", id).addValue("aggType", aggregateType)
                    .addValue("aggId", String.valueOf(aggregateId)).addValue("type", eventType)
                    .addValue("payload", json.writeValueAsString(payload)).addValue("now", now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event payload not serialisable: " + eventType, e);
        }
        return id;
    }
}
