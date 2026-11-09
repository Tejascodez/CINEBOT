package com.cinebot.show;

import com.cinebot.common.time.BusinessTime;
import com.cinebot.movie.NowShowingQuery;
import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import com.cinebot.show.dto.ShowResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for show discovery, in plain SQL. Listing shows needs a join across 5 tables plus aggregates
 * (available seats, min price); one GROUP BY query is far cheaper than loading entity graphs.
 * Writes still go through JPA entities in ShowService.
 */
@Repository
public class ShowQueryRepository implements NowShowingQuery {

    static final int MAX_RESULTS = 200;

    private static final String BASE_SELECT = """
            SELECT s.id, s.start_time, s.end_time, s.language, s.format, s.status,
                   m.id AS movie_id, m.title AS movie_title,
                   t.id AS theatre_id, t.name AS theatre_name, t.city,
                   sc.id AS screen_id, sc.name AS screen_name, sc.screen_type,
                   count(ss.id)                                            AS total_seats,
                   count(ss.id) FILTER (WHERE ss.status = 'AVAILABLE'
                                  OR (ss.status = 'LOCKED' AND ss.locked_until <= :now)) AS available_seats,
                   min(ss.price_paise)                                     AS min_price
            FROM shows s
            JOIN movies   m  ON m.id  = s.movie_id
            JOIN screens  sc ON sc.id = s.screen_id
            JOIN theatres t  ON t.id  = sc.theatre_id
            LEFT JOIN show_seats ss ON ss.show_id = s.id
            """;

    // Grouping by primary keys lets Postgres select their functionally dependent columns.
    private static final String GROUP_BY = " GROUP BY s.id, m.id, t.id, sc.id ";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ShowQueryRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Scheduled shows in active theatres starting within [from, to), ordered by theatre then time. */
    @Transactional(readOnly = true)
    public List<ShowResponse> search(Long movieId, Long theatreId, String city, Instant from, Instant to) {
        StringBuilder where = new StringBuilder("""
                WHERE s.status = 'SCHEDULED' AND t.active
                  AND s.start_time >= :from AND s.start_time < :to
                """);
        var params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("now", Timestamp.from(clock.instant()))
                .addValue("limit", MAX_RESULTS);
        if (movieId != null) {
            where.append(" AND s.movie_id = :movieId");
            params.addValue("movieId", movieId);
        }
        if (theatreId != null) {
            where.append(" AND t.id = :theatreId");
            params.addValue("theatreId", theatreId);
        }
        if (city != null) {
            where.append(" AND lower(t.city) = lower(:city)");
            params.addValue("city", city);
        }
        String sql = BASE_SELECT + where + GROUP_BY + " ORDER BY t.name, s.start_time, s.id LIMIT :limit";
        return jdbc.query(sql, params, (rs, i) -> map(rs));
    }

    @Transactional(readOnly = true)
    public Optional<ShowResponse> findById(Long showId) {
        List<ShowResponse> found = jdbc.query(BASE_SELECT + " WHERE s.id = :id " + GROUP_BY,
                new MapSqlParameterSource("id", showId).addValue("now", Timestamp.from(clock.instant())),
                (rs, i) -> map(rs));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<ShowResponse.Price> prices = jdbc.query("""
                SELECT se.seat_type, min(ss.price_paise) AS price
                FROM show_seats ss JOIN seats se ON se.id = ss.seat_id
                WHERE ss.show_id = :id
                GROUP BY se.seat_type
                ORDER BY price
                """, new MapSqlParameterSource("id", showId),
                (rs, i) -> new ShowResponse.Price(SeatType.valueOf(rs.getString("seat_type")), rs.getLong("price")));
        return Optional.of(found.getFirst().withPrices(prices));
    }

    /** Batch lookup (e.g. a page of bookings) in one query instead of one per row. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, ShowResponse> findByIds(java.util.Collection<Long> showIds) {
        if (showIds.isEmpty()) {
            return java.util.Map.of();
        }
        var params = new MapSqlParameterSource("ids", showIds).addValue("now", Timestamp.from(clock.instant()));
        return jdbc.query(BASE_SELECT + " WHERE s.id IN (:ids) " + GROUP_BY, params, (rs, i) -> map(rs)).stream()
                .collect(java.util.stream.Collectors.toMap(ShowResponse::id, r -> r));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> movieIdsShowingInCity(String city) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT s.movie_id
                FROM shows s
                JOIN screens sc ON sc.id = s.screen_id
                JOIN theatres t ON t.id = sc.theatre_id
                WHERE s.status = 'SCHEDULED' AND t.active AND s.start_time > :now AND lower(t.city) = lower(:city)
                """, new MapSqlParameterSource().addValue("now", Timestamp.from(clock.instant())).addValue("city", city),
                Long.class));
    }

    private static ShowResponse map(ResultSet rs) throws SQLException {
        return new ShowResponse(
                rs.getLong("id"),
                rs.getLong("movie_id"), rs.getString("movie_title"),
                rs.getLong("theatre_id"), rs.getString("theatre_name"), rs.getString("city"),
                rs.getLong("screen_id"), rs.getString("screen_name"), ScreenType.valueOf(rs.getString("screen_type")),
                BusinessTime.toLocal(rs.getTimestamp("start_time").toInstant()),
                BusinessTime.toLocal(rs.getTimestamp("end_time").toInstant()),
                rs.getString("language"), rs.getString("format"), ShowStatus.valueOf(rs.getString("status")),
                rs.getInt("total_seats"), rs.getInt("available_seats"),
                rs.getObject("min_price", Long.class),   // NULL when a show has no inventory
                null);
    }
}
