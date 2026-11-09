package com.cinebot.booking.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.support.VenueTestSupport;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Single-user locking rules, Redis/DB agreement, lazy expiry and the sweeper. */
class SeatLockingIT extends VenueTestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SeatLockService seatLocks;
    @Autowired
    private ExpiredLockSweep sweep;

    private long show;
    private AuthenticatedUser alice;
    private AuthenticatedUser bob;

    @BeforeEach
    void setUp() {
        long screen = screenWithLayout(theatre("PVR Velachery", "Chennai"), "S1");
        show = show(movie("Interstellar", 150), screen, ist(tomorrow(), 18, 0)).id();
        alice = user("alice");
        bob = user("bob");
    }

    @Test
    void locksSeatsOverHttpAndSeatMapShowsThem() throws Exception {
        String body = "{\"showId\":%d,\"showSeatIds\":[%d,%d]}".formatted(show, seat(show, "B1"), seat(show, "A1"));

        mvc.perform(post("/api/v1/seat-locks").header("Authorization", bearerFor(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lockId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seats[*].label").value(contains("A1", "B1")))
                .andExpect(jsonPath("$.totalPaise").value(20_000 + 35_000))
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.endsWith("+05:30")));

        mvc.perform(get("/api/v1/shows/{id}/seats", show))
                .andExpect(jsonPath("$.availableSeats").value(3))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("LOCKED"));
        assertThat(redis.hasKey(RedisSeatLockGate.key(show, seat(show, "A1")))).isTrue();
    }

    @Test
    void lockingRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/seat-locks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":%d,\"showSeatIds\":[%d]}".formatted(show, seat(show, "A1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void secondUserGetsConflictNamingTheSeat() throws Exception {
        long a1 = seat(show, "A1");
        lock(alice, a1);

        mvc.perform(post("/api/v1/seat-locks").header("Authorization", bearerFor(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":%d,\"showSeatIds\":[%d,%d]}".formatted(show, a1, seat(show, "A2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableSeatIds").value(contains((int) a1)));
        // all-or-nothing: A2 was not locked for bob
        assertThat(statusOf(seat(show, "A2"))).isEqualTo("AVAILABLE");
    }

    @Test
    void databaseRejectsEvenWhenRedisKeyIsMissing() {
        long a1 = seat(show, "A1");
        lock(alice, a1);
        redis.delete(RedisSeatLockGate.key(show, a1)); // e.g. Redis restarted and lost its keys

        assertCode(() -> lock(bob, a1), ErrorCode.SEATS_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_locks WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1); // bob's lock row was rolled back with the failed attempt
        assertThat(redis.hasKey(RedisSeatLockGate.key(show, a1))).isFalse(); // and his Redis claim cleaned up
    }

    @Test
    void releaseFreesSeatsInDbAndRedis() throws Exception {
        long a1 = seat(show, "A1");
        SeatLockResponse lock = lock(alice, a1);

        mvc.perform(delete("/api/v1/seat-locks/{id}", lock.lockId()).header("Authorization", bearerFor(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/seat-locks/{id}", lock.lockId()).header("Authorization", bearerFor(alice)))
                .andExpect(status().isNoContent()); // idempotent

        assertThat(statusOf(a1)).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(RedisSeatLockGate.key(show, a1))).isFalse();
        lock(bob, a1); // now bookable by someone else
    }

    @Test
    void cannotReleaseSomeoneElsesLock() throws Exception {
        SeatLockResponse lock = lock(alice, seat(show, "A1"));

        mvc.perform(delete("/api/v1/seat-locks/{id}", lock.lockId()).header("Authorization", bearerFor(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/seat-locks/{id}", UUID.randomUUID()).header("Authorization", bearerFor(bob)))
                .andExpect(status().isNotFound());
        assertThat(statusOf(seat(show, "A1"))).isEqualTo("LOCKED");
    }

    @Test
    void oneActiveLockPerUserPerShow() {
        SeatLockResponse first = lock(alice, seat(show, "A1"));

        assertThatThrownBy(() -> lock(alice, seat(show, "A2")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.ACTIVE_LOCK_EXISTS);
                    assertThat(((ApiException) e).properties()).containsEntry("lockId", first.lockId());
                });
        seatLocks.release(alice, first.lockId());
        lock(alice, seat(show, "A2"));
    }

    @Test
    void ownExpiredLockIsReplacedAutomatically() {
        SeatLockResponse old = lock(alice, seat(show, "A1"));
        expireNow(old.lockId());

        lock(alice, seat(show, "A2"));

        assertThat(jdbc.queryForObject("SELECT status FROM seat_locks WHERE id = ?", String.class, old.lockId()))
                .isEqualTo("EXPIRED");
        assertThat(statusOf(seat(show, "A1"))).isEqualTo("AVAILABLE");
    }

    @Test
    void expiredLockIsFreeImmediatelyWithoutWaitingForSweeper() throws Exception {
        long a1 = seat(show, "A1");
        SeatLockResponse old = lock(alice, a1);
        expireNow(old.lockId());

        mvc.perform(get("/api/v1/shows/{id}/seats", show))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.availableSeats").value(5));
        SeatLockResponse taken = lock(bob, a1); // takes over the expired hold

        assertThat(jdbc.queryForObject("SELECT lock_id FROM show_seats WHERE id = ?", UUID.class, a1))
                .isEqualTo(taken.lockId());
        // alice's stale lock is closed later by the sweeper, which must NOT free bob's seat
        assertThat(sweep.sweepBatch()).isEqualTo(1);
        assertThat(statusOf(a1)).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT lock_id FROM show_seats WHERE id = ?", UUID.class, a1))
                .isEqualTo(taken.lockId());
    }

    @Test
    void sweeperExpiresOnlyDueLocks() {
        SeatLockResponse due = lock(alice, seat(show, "A1"));
        lock(bob, seat(show, "A2"));
        expireNow(due.lockId());

        assertThat(sweep.sweepBatch()).isEqualTo(1);
        assertThat(sweep.sweepBatch()).isZero();

        assertThat(statusOf(seat(show, "A1"))).isEqualTo("AVAILABLE");
        assertThat(statusOf(seat(show, "A2"))).isEqualTo("LOCKED");
        assertThat(jdbc.queryForList("SELECT status FROM seat_locks ORDER BY created_at", String.class))
                .containsExactly("EXPIRED", "ACTIVE");
    }

    @Test
    void cancelledOrStartedShowsAreNotBookable() {
        showService.cancel(show);
        assertCode(() -> lock(alice, seat(show, "A1")), ErrorCode.SHOW_NOT_BOOKABLE);

        long screen2 = screenWithLayout(theatre("Other", "Chennai"), "S1");
        long soon = show(movie("Soon", 90), screen2, java.time.OffsetDateTime.now().plusMinutes(1)).id();
        jdbc.update("UPDATE shows SET start_time = now() - interval '1 minute' WHERE id = ?", soon);
        assertCode(() -> lock(alice, seat(soon, "A1")), ErrorCode.SHOW_NOT_BOOKABLE);
    }

    @Test
    void selectionValidation() {
        long otherShow = show(movie("Other", 90), screenWithLayout(theatre("T2", "Chennai"), "S"),
                ist(tomorrow(), 10, 0)).id();
        assertCode(() -> lock(alice, seat(otherShow, "A1")), ErrorCode.INVALID_SEAT_SELECTION);
        assertCode(() -> seatLocks.lock(alice, new LockSeatsRequest(show, List.of(seat(show, "A1"), seat(show, "A1")))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> lock(alice, 987654L), ErrorCode.INVALID_SEAT_SELECTION);
        assertCode(() -> seatLocks.lock(alice, new LockSeatsRequest(987654L, List.of(1L))), ErrorCode.SHOW_NOT_FOUND);
    }

    @Test
    void databaseConstraintsRejectInconsistentInventory() {
        long a1 = seat(show, "A1");
        assertThatThrownBy(() -> jdbc.update("UPDATE show_seats SET status = 'LOCKED' WHERE id = ?", a1))
                .hasMessageContaining("ck_show_seats_locked");
        assertThatThrownBy(() -> jdbc.update("UPDATE show_seats SET status = 'BOOKED' WHERE id = ?", a1))
                .hasMessageContaining("ck_show_seats_booked");
    }

    private SeatLockResponse lock(AuthenticatedUser user, long... seatIds) {
        return seatLocks.lock(user, new LockSeatsRequest(show, java.util.Arrays.stream(seatIds).boxed().toList()));
    }

    private void expireNow(UUID lockId) {
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '1 second' WHERE id = ?", lockId);
        jdbc.update("UPDATE show_seats SET locked_until = now() - interval '1 second' WHERE lock_id = ?", lockId);
        redis.keys("cinebot:seatlock:*").forEach(redis::delete); // Redis TTL would have expired too
    }

    private String statusOf(long showSeatId) {
        return jdbc.queryForObject("SELECT status FROM show_seats WHERE id = ?", String.class, showSeatId);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(code);
    }
}
