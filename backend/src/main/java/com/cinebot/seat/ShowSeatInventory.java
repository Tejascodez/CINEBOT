package com.cinebot.seat;

import com.cinebot.screen.SeatType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory primitives used by the booking engine. All state changes are CONDITIONAL updates: the WHERE clause
 * states what must still be true, and the affected-row count tells the caller whether it won.
 * Every method requires the caller's transaction (MANDATORY): these are steps of a larger atomic operation.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ShowSeatInventory {

    private static final String SEAT_LINE_SELECT = """
            SELECT ss.id, se.row_label || se.seat_number AS label, se.seat_type, ss.price_paise
            FROM show_seats ss JOIN seats se ON se.id = ss.seat_id
            """;
    private static final RowMapper<SeatLine> SEAT_LINE = (rs, i) -> new SeatLine(rs.getLong("id"),
            rs.getString("label"), SeatType.valueOf(rs.getString("seat_type")), rs.getLong("price_paise"));

    private final NamedParameterJdbcTemplate jdbc;

    public ShowSeatInventory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The requested seats that really belong to the show (for validation and pricing). */
    public List<SeatLine> describe(Long showId, Collection<Long> showSeatIds) {
        return jdbc.query(SEAT_LINE_SELECT + " WHERE ss.show_id = :showId AND ss.id IN (:ids) ORDER BY ss.id",
                new MapSqlParameterSource("showId", showId).addValue("ids", showSeatIds), SEAT_LINE);
    }

    /**
     * Row-locks the requested seats in ascending id order, SKIPPING rows another transaction already holds.
     * <ul>
     *   <li>Fail fast: a seat someone else is mid-way through locking is reported unavailable at once, instead of
     *       this request queueing behind the other transaction's commit.</li>
     *   <li>Deterministic lock order: PostgreSQL does not guarantee the order in which an UPDATE locks rows
     *       (it depends on the plan), so overlapping requests (A1,A2 vs A2,A3) could deadlock under a different
     *       plan. Locking explicitly in id order first rules that out.</li>
     * </ul>
     * Correctness does not depend on this step: the conditional UPDATE in {@link #lock} is the authority.
     * (Verified: with this step removed, the overlap tests still pass; they just wait instead of failing fast.)
     * @return ids actually row-locked by this transaction
     */
    public List<Long> rowLockFree(Long showId, Collection<Long> sortedShowSeatIds) {
        return jdbc.queryForList("""
                SELECT id FROM show_seats
                WHERE show_id = :showId AND id IN (:ids)
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("showId", showId).addValue("ids", sortedShowSeatIds), Long.class);
    }

    /**
     * The authoritative lock: succeeds only for seats that are AVAILABLE, or LOCKED by a lock that has
     * expired (lazy expiry: an expired lock can be taken over before the sweeper runs).
     * @return number of seats now held by {@code lockId}; the caller must compare it with the request size.
     */
    public int lock(Long showId, Collection<Long> showSeatIds, UUID lockId, Instant lockedUntil, Instant now) {
        return jdbc.update("""
                UPDATE show_seats
                SET status = 'LOCKED', lock_id = :lockId, locked_until = :until, version = version + 1
                WHERE show_id = :showId AND id IN (:ids)
                  AND (status = 'AVAILABLE' OR (status = 'LOCKED' AND locked_until <= :now))
                """, new MapSqlParameterSource()
                .addValue("showId", showId).addValue("ids", showSeatIds).addValue("lockId", lockId)
                .addValue("until", Timestamp.from(lockedUntil)).addValue("now", Timestamp.from(now)));
    }

    /** Seats currently held by the lock (may be fewer than requested if it expired and was taken over). */
    public List<SeatLine> heldBy(UUID lockId) {
        return jdbc.query(SEAT_LINE_SELECT
                        + " WHERE ss.lock_id = :lockId AND ss.status = 'LOCKED' ORDER BY se.row_index, se.seat_number",
                new MapSqlParameterSource("lockId", lockId), SEAT_LINE);
    }

    /**
     * Converts the lock's seats into sold seats for the booking, but only if the lock still holds ALL of them.
     * Checks ownership (lock_id), not the clock: a lock that expired but was not taken over still confirms.
     * If any seat was taken over after expiry, nothing is changed and the caller refunds.
     * @return the booked seat ids, or empty if the lock no longer holds every seat
     */
    public List<Long> bookIfStillHeld(UUID lockId, Long bookingId, int expectedSeats) {
        var params = new MapSqlParameterSource("lockId", lockId).addValue("bookingId", bookingId);
        List<Long> held = jdbc.queryForList("""
                SELECT id FROM show_seats WHERE lock_id = :lockId AND status = 'LOCKED' ORDER BY id FOR UPDATE
                """, params, Long.class);
        if (held.size() != expectedSeats) {
            return List.of();
        }
        int booked = jdbc.update("""
                UPDATE show_seats
                SET status = 'BOOKED', booking_id = :bookingId, lock_id = NULL, locked_until = NULL, version = version + 1
                WHERE lock_id = :lockId AND status = 'LOCKED'
                """, params);
        if (booked != expectedSeats) {
            throw new IllegalStateException("Booked " + booked + " of " + expectedSeats + " row-locked seats");
        }
        return held;
    }

    /** Refund of a confirmed booking: its sold seats go back on sale. */
    public int unbook(Long bookingId) {
        return jdbc.update("""
                UPDATE show_seats SET status = 'AVAILABLE', booking_id = NULL, version = version + 1
                WHERE booking_id = :bookingId AND status = 'BOOKED'
                """, new MapSqlParameterSource("bookingId", bookingId));
    }

    /**
     * Frees only rows still held by THIS lock, so releasing a lock can never free a seat someone else
     * has taken over in the meantime.
     */
    public int release(UUID lockId) {
        return jdbc.update("""
                UPDATE show_seats
                SET status = 'AVAILABLE', lock_id = NULL, locked_until = NULL, version = version + 1
                WHERE lock_id = :lockId AND status = 'LOCKED'
                """, new MapSqlParameterSource("lockId", lockId));
    }
}
