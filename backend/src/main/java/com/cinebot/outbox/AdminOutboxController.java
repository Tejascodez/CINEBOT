package com.cinebot.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Dead-letter inspection and replay. Replay is safe: handlers that already succeeded are skipped. */
@RestController
@RequestMapping("/api/v1/admin/outbox")
public class AdminOutboxController {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AdminOutboxController(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record DeadEvent(UUID id, String type, String aggregateId, int attempts, String lastError, Instant createdAt) {
    }

    public record Summary(long pending, long dead, long done) {
    }

    @GetMapping("/summary")
    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'DEAD') AS dead,
                       count(*) FILTER (WHERE status = 'DONE') AS done
                FROM outbox_events""", new MapSqlParameterSource(),
                (rs, i) -> new Summary(rs.getLong("pending"), rs.getLong("dead"), rs.getLong("done")));
    }

    @GetMapping("/dead")
    public List<DeadEvent> dead() {
        return jdbc.query("""
                SELECT id, event_type, aggregate_id, attempts, last_error, created_at FROM outbox_events
                WHERE status = 'DEAD' ORDER BY created_at LIMIT 100""", new MapSqlParameterSource(),
                (rs, i) -> new DeadEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_id"), rs.getInt("attempts"), rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable UUID id) {
        int updated = jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING', attempts = 0, available_at = :now, last_error = NULL
                WHERE id = :id AND status = 'DEAD'""",
                new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(clock.instant())));
        if (updated == 0) {
            throw new com.cinebot.common.error.ApiException(com.cinebot.common.error.ErrorCode.NOT_FOUND,
                    "No dead event " + id);
        }
    }
}
