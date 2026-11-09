package com.cinebot.booking.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.screen.SeatType;
import com.cinebot.screen.dto.SeatLayoutRequest;
import com.cinebot.support.Race;
import com.cinebot.support.VenueTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * "Never claim concurrency safety without testing it." Every test releases all threads at the same instant
 * and then checks invariants directly in the database.
 */
class SeatLockConcurrencyIT extends VenueTestSupport {

    @Autowired
    private SeatLockService seatLocks;

    private long movie;
    private long theatre;
    private long screen;
    private long show;

    @BeforeEach
    void setUp() {
        movie = movie("Interstellar", 150);
        theatre = theatre("PVR Velachery", "Chennai");
        screen = theatreService.addScreen(theatre,
                new com.cinebot.screen.dto.ScreenRequest("Big", com.cinebot.screen.ScreenType.STANDARD)).id();
        screenService.replaceLayout(screen, new SeatLayoutRequest(List.of(
                new SeatLayoutRequest.Row("A", 50, SeatType.REGULAR),
                new SeatLayoutRequest.Row("B", 50, SeatType.PREMIUM))));
        show = show(movie, screen, ist(tomorrow(), 18, 0)).id();
    }

    @Test
    void twentyUsersSameSeatExactlyOneWins() throws Exception {
        long a1 = seat(show, "A1");
        List<Callable<SeatLockResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            AuthenticatedUser u = user("racer" + i);
            attempts.add(() -> seatLocks.lock(u, new LockSeatsRequest(show, List.of(a1))));
        }

        var outcomes = Race.run(attempts);

        assertThat(outcomes.stream().filter(Race.Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((ApiException) o.error()).code()))
                .hasSize(19).containsOnly(ErrorCode.SEATS_UNAVAILABLE);
        assertInventoryConsistent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_locks", Integer.class)).isEqualTo(1);
    }

    /**
     * Overlapping multi-seat requests (A1+A2 vs A2+A3 ...) in random combinations. Checks all-or-nothing
     * (no lock holds a subset of what it asked for), no double ownership, and no deadlock errors.
     */
    @Test
    void overlappingMultiSeatRequestsAreAllOrNothingAndNeverDeadlock() throws Exception {
        List<Long> pool = allSeats(show).subList(0, 8);
        Random random = new Random(42);
        Map<Integer, List<Long>> requested = new HashMap<>();
        List<Callable<SeatLockResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Long> pick = new ArrayList<>(pool);
            java.util.Collections.shuffle(pick, random);
            List<Long> seats = pick.subList(0, 2 + random.nextInt(3)); // 2..4 seats, unsorted on purpose
            requested.put(i, seats);
            AuthenticatedUser u = user("multi" + i);
            attempts.add(() -> seatLocks.lock(u, new LockSeatsRequest(show, seats)));
        }

        var outcomes = Race.run(attempts);

        int lockedSeats = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            var o = outcomes.get(i);
            if (o.succeeded()) {
                List<Long> held = jdbc.queryForList("SELECT id FROM show_seats WHERE lock_id = ?", Long.class,
                        o.value().lockId());
                assertThat(held).containsExactlyInAnyOrderElementsOf(requested.get(i));
                lockedSeats += held.size();
            } else {
                assertThat(o.error()).isInstanceOf(ApiException.class); // never a deadlock / SQL error
                assertThat(((ApiException) o.error()).code()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
            }
        }
        assertThat(outcomes.stream().filter(Race.Outcome::succeeded)).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE status = 'LOCKED'", Integer.class))
                .isEqualTo(lockedSeats);
        assertInventoryConsistent();
    }

    @Test
    void manyUsersDifferentSeatsAllSucceed() throws Exception {
        List<Long> seats = allSeats(show).subList(0, 50);
        List<Callable<SeatLockResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            AuthenticatedUser u = user("solo" + i);
            long seat = seats.get(i);
            attempts.add(() -> seatLocks.lock(u, new LockSeatsRequest(show, List.of(seat))));
        }

        var outcomes = Race.run(attempts);

        assertThat(outcomes).allMatch(Race.Outcome::succeeded); // no false conflicts between unrelated seats
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE status = 'LOCKED'", Integer.class))
                .isEqualTo(50);
    }

    /**
     * Cancel vs. lock race. Without the FOR SHARE / FOR UPDATE pair on the show row, a lock attempt that
     * started before the cancel committed could leave a CANCELLED show with LOCKED seats.
     */
    @Test
    void showCancellationAndSeatLockingNeverInterleave() throws Exception {
        for (int round = 0; round < 15; round++) {
            long s = show(movie, screen, ist(tomorrow().plusDays(1), 0, 0).plusHours(round * 3L)).id();
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> {
                try {
                    return showService.cancel(s);
                } catch (ApiException e) {
                    return e.code(); // SHOW_HAS_BOOKINGS if a lock won the race: also fine
                }
            });
            for (int i = 0; i < 5; i++) {
                AuthenticatedUser u = user("c" + round + "_" + i);
                long seat = allSeats(s).get(i);
                tasks.add(() -> seatLocks.lock(u, new LockSeatsRequest(s, List.of(seat))));
            }
            Race.run(tasks);

            String status = jdbc.queryForObject("SELECT status FROM shows WHERE id = ?", String.class, s);
            Integer locked = jdbc.queryForObject(
                    "SELECT count(*) FROM show_seats WHERE show_id = ? AND status = 'LOCKED'", Integer.class, s);
            if ("CANCELLED".equals(status)) {
                assertThat(locked).as("round %d: cancelled show must hold no locked seats", round).isZero();
            }
        }
    }

    @Test
    void sameUserDoubleClickCreatesOneLock() throws Exception {
        AuthenticatedUser u = user("clicker");
        List<Long> seats = allSeats(show).subList(0, 2);

        var outcomes = Race.run(List.<Callable<SeatLockResponse>>of(
                () -> seatLocks.lock(u, new LockSeatsRequest(show, seats)),
                () -> seatLocks.lock(u, new LockSeatsRequest(show, seats)),
                () -> seatLocks.lock(u, new LockSeatsRequest(show, seats))));

        assertThat(outcomes.stream().filter(Race.Outcome::succeeded)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_locks WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    /** Global invariants that must hold after any race. */
    private void assertInventoryConsistent() {
        // every LOCKED seat points at an ACTIVE lock of the same show
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM show_seats ss LEFT JOIN seat_locks l ON l.id = ss.lock_id
                WHERE ss.status = 'LOCKED' AND (l.status IS DISTINCT FROM 'ACTIVE' OR l.show_id <> ss.show_id)
                """, Integer.class)).isZero();
        // every ACTIVE lock holds exactly the number of seats it was granted
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seat_locks l
                WHERE l.status = 'ACTIVE'
                  AND l.seat_count <> (SELECT count(*) FROM show_seats ss WHERE ss.lock_id = l.id)
                """, Integer.class)).isZero();
        // every Redis key belongs to a lock that exists in the DB (failed attempts cleaned up after themselves)
        for (String key : redis.keys("cinebot:seatlock:*")) {
            UUID owner = UUID.fromString(redis.opsForValue().get(key));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_locks WHERE id = ?", Integer.class, owner))
                    .as("Redis key %s", key).isEqualTo(1);
        }
    }
}
