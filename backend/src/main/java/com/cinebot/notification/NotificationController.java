package com.cinebot.notification;

import com.cinebot.auth.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's inbox: the emails we sent them (most recent 50). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NotificationResponse(Long id, String kind, String subject, String body, Instant createdAt) {
    }

    @GetMapping("/my")
    public List<NotificationResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return jdbc.query("""
                SELECT id, kind, subject, body, created_at FROM notifications
                WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT 50
                """, new MapSqlParameterSource("userId", user.id()),
                (rs, i) -> new NotificationResponse(rs.getLong("id"), rs.getString("kind"), rs.getString("subject"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant()));
    }
}
