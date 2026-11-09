package com.cinebot.analytics;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Eventually consistent (updated by the outbox relay within ~1 s): fine for a dashboard, never for booking logic. */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminStatsController {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminStatsController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ShowStats(Long showId, int bookingsConfirmed, int seatsSold, long revenuePaise, int refunds) {
    }

    @GetMapping("/shows/{showId}")
    public ShowStats show(@PathVariable Long showId) {
        return jdbc.query("SELECT * FROM show_sales_stats WHERE show_id = :id", new MapSqlParameterSource("id", showId),
                        (rs, i) -> new ShowStats(showId, rs.getInt("bookings_confirmed"), rs.getInt("seats_sold"),
                                rs.getLong("revenue_paise"), rs.getInt("refunds")))
                .stream().findFirst().orElse(new ShowStats(showId, 0, 0, 0, 0));
    }
}
