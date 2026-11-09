package com.cinebot.analytics;

import com.cinebot.booking.BookingEvent;
import com.cinebot.booking.BookingStatus;
import com.cinebot.outbox.EventHandler;
import com.cinebot.outbox.OutboxMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-show sales counters. Increments are commutative (order of events does not matter) but NOT idempotent,
 * so correctness relies on processed_events: a redelivered "confirmed" must never count a sale twice.
 */
@Component
class SalesAnalyticsHandler implements EventHandler {

    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    SalesAnalyticsHandler(ObjectMapper json, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "sales-analytics";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(BookingEvent.CONFIRMED, BookingEvent.REFUND_REQUESTED);
    }

    @Override
    public void handle(OutboxMessage message) {
        BookingEvent e;
        try {
            e = json.readValue(message.payload(), BookingEvent.class);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Unreadable payload of event " + message.id(), ex);
        }
        int sign;
        if (BookingEvent.CONFIRMED.equals(message.type())) {
            sign = 1;
        } else if (e.fromStatus() == BookingStatus.CONFIRMED) {
            sign = -1; // a sale that is being refunded; late-capture refunds were never counted as sales
        } else {
            return;
        }
        jdbc.update("""
                INSERT INTO show_sales_stats (show_id, bookings_confirmed, seats_sold, revenue_paise, refunds, updated_at)
                VALUES (:showId, :bookings, :seats, :revenue, :refunds, :now)
                ON CONFLICT (show_id) DO UPDATE SET
                    bookings_confirmed = show_sales_stats.bookings_confirmed + EXCLUDED.bookings_confirmed,
                    seats_sold         = show_sales_stats.seats_sold + EXCLUDED.seats_sold,
                    revenue_paise      = show_sales_stats.revenue_paise + EXCLUDED.revenue_paise,
                    refunds            = show_sales_stats.refunds + EXCLUDED.refunds,
                    updated_at         = EXCLUDED.updated_at
                """, new MapSqlParameterSource("showId", e.showId()).addValue("bookings", sign)
                .addValue("seats", sign * e.seats().size()).addValue("revenue", sign * e.totalPaise())
                .addValue("refunds", sign < 0 ? 1 : 0).addValue("now", Timestamp.from(clock.instant())));
    }
}
