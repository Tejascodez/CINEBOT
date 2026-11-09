package com.cinebot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.lock.ExpiredLockSweep;
import com.cinebot.booking.lock.SeatLockService;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.support.Race;
import com.cinebot.support.VenueTestSupport;
import com.cinebot.user.Role;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Lock -> pending booking -> cancel / expiry, ownership, idempotency. */
class BookingFlowIT extends VenueTestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SeatLockService seatLocks;
    @Autowired
    private BookingService bookingService;
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
    void createsPendingBookingFromLock() throws Exception {
        SeatLockResponse lock = lock(alice, "A1", "B1");

        createBooking(alice, lock.lockId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("^CB[2-9A-Z]{8}$")))
                .andExpect(jsonPath("$.totalPaise").value(55_000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.seats[*].label").value(contains("A1", "B1")))
                .andExpect(jsonPath("$.show.movieTitle").value("Interstellar"))
                .andExpect(jsonPath("$.show.theatreName").value("PVR Velachery"))
                .andExpect(jsonPath("$.expiresAt").value(lock.expiresAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }

    @Test
    void repeatingCreateReturnsSameBookingWith200() throws Exception {
        SeatLockResponse lock = lock(alice, "A1");
        long id = id(createBooking(alice, lock.lockId()).andExpect(status().isCreated()));

        long again = id(createBooking(alice, lock.lockId()).andExpect(status().isOk()));

        assertThat(again).isEqualTo(id);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void concurrentCreateForSameLockYieldsOneBooking() throws Exception {
        UUID lockId = lock(alice, "A1").lockId();

        var outcomes = Race.run(List.<Callable<BookingService.CreatedBooking>>of(
                () -> bookingService.create(alice, lockId), () -> bookingService.create(alice, lockId),
                () -> bookingService.create(alice, lockId), () -> bookingService.create(alice, lockId)));

        assertThat(outcomes).allMatch(Race.Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> o.value().created())).hasSize(1);
        assertThat(outcomes.stream().map(o -> o.value().booking().id()).distinct()).hasSize(1);
        assertThat(count("bookings")).isEqualTo(1);
    }

    @Test
    void cannotBookWithSomeoneElsesLock() throws Exception {
        SeatLockResponse lock = lock(alice, "A1");

        createBooking(bob, lock.lockId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCK_NOT_FOUND"));
    }

    @Test
    void expiredLockCannotBecomeABooking() throws Exception {
        SeatLockResponse lock = lock(alice, "A1");
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '1 second' WHERE id = ?", lock.lockId());
        jdbc.update("UPDATE show_seats SET locked_until = now() - interval '1 second' WHERE lock_id = ?", lock.lockId());

        createBooking(alice, lock.lockId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_EXPIRED"));
    }

    @Test
    void lockTakenOverAfterExpiryCannotBecomeABooking() {
        SeatLockResponse old = lock(alice, "A1");
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '1 second' WHERE id = ?", old.lockId());
        jdbc.update("UPDATE show_seats SET locked_until = now() - interval '1 second' WHERE lock_id = ?", old.lockId());
        redis.keys("cinebot:seatlock:*").forEach(redis::delete);
        lock(bob, "A1");

        assertThatThrownBy(() -> bookingService.create(alice, old.lockId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.LOCK_EXPIRED);
    }

    @Test
    void otherUsersBookingsAreInvisibleButAdminsCanSee() throws Exception {
        long id = id(createBooking(alice, lock(alice, "A1").lockId()));

        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", bearerFor(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", bearerFor(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", bearerFor(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void myBookingsArePagedNewestFirstAndFilterable() throws Exception {
        long first = id(createBooking(alice, lock(alice, "A1").lockId()));
        bookingService.cancel(alice, first);
        long second = id(createBooking(alice, lock(alice, "A2").lockId()));
        createBooking(bob, lock(bob, "A3").lockId());

        mvc.perform(get("/api/v1/bookings/my").header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) second, (int) first)))
                .andExpect(jsonPath("$.content[0].show.movieTitle").value("Interstellar"));
        mvc.perform(get("/api/v1/bookings/my").param("status", "CANCELLED").header("Authorization", bearerFor(alice)))
                .andExpect(jsonPath("$.content[*].id").value(contains((int) first)));
    }

    @Test
    void cancelReleasesSeatsAndIsIdempotent() throws Exception {
        SeatLockResponse lock = lock(alice, "A1", "A2");
        long id = id(createBooking(alice, lock.lockId()));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM show_seats WHERE show_id = ?", String.class, show))
                .containsExactly("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM seat_locks WHERE id = ?", String.class, lock.lockId()))
                .isEqualTo("RELEASED");
        assertThat(redis.keys("cinebot:seatlock:*")).isEmpty();
        lock(bob, "A1");
    }

    @Test
    void lockBackingAnOpenBookingCannotBeReleasedDirectly() throws Exception {
        SeatLockResponse lock = lock(alice, "A1");
        createBooking(alice, lock.lockId());

        mvc.perform(delete("/api/v1/seat-locks/{id}", lock.lockId()).header("Authorization", bearerFor(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_IN_USE"));
    }

    @Test
    void sweeperExpiresPendingBookingAndFreesSeats() throws Exception {
        SeatLockResponse lock = lock(alice, "A1");
        long id = id(createBooking(alice, lock.lockId()));
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '1 second' WHERE id = ?", lock.lockId());

        assertThat(sweep.sweepBatch()).isEqualTo(1);

        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", bearerFor(alice)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE id = ?", String.class, seat(show, "A1")))
                .isEqualTo("AVAILABLE");
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("Authorization", bearerFor(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void concurrentSweepersNeverProcessTheSameLockTwice() throws Exception {
        for (String label : List.of("A1", "A2", "A3", "B1", "B2")) {
            AuthenticatedUser u = user("u" + label);
            UUID lockId = seatLocks.lock(u, new LockSeatsRequest(show, List.of(seat(show, label)))).lockId();
            bookingService.create(u, lockId);
        }
        jdbc.update("UPDATE seat_locks SET expires_at = now() - interval '1 second'");

        var outcomes = Race.run(List.<Callable<Integer>>of(sweep::sweepBatch, sweep::sweepBatch, sweep::sweepBatch));

        assertThat(outcomes).allMatch(Race.Outcome::succeeded);
        assertThat(outcomes.stream().mapToInt(Race.Outcome::value).sum()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM bookings", String.class)).containsExactly("EXPIRED");
    }

    @Test
    void confirmedSeatCanBelongToOnlyOneBookingEvenIfCodeWereWrong() {
        long a1 = seat(show, "A1");
        Long first = bookingService.create(alice, lock(alice, "A1").lockId()).booking().id();
        bookingService.cancel(alice, first);
        Long second = bookingService.create(bob, lock(bob, "A1").lockId()).booking().id();
        jdbc.update("UPDATE booking_seats SET confirmed = true WHERE booking_id = ?", first);

        assertThatThrownBy(() -> jdbc.update("UPDATE booking_seats SET confirmed = true WHERE booking_id = ?", second))
                .hasMessageContaining("uk_booking_seats_confirmed_once");
        assertThat(a1).isPositive();
    }

    private SeatLockResponse lock(AuthenticatedUser user, String... labels) {
        List<Long> ids = java.util.Arrays.stream(labels).map(l -> seat(show, l)).toList();
        return seatLocks.lock(user, new LockSeatsRequest(show, ids));
    }

    private ResultActions createBooking(AuthenticatedUser user, UUID lockId) throws Exception {
        return mvc.perform(post("/api/v1/bookings").header("Authorization", bearerFor(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lockId\":\"" + lockId + "\"}"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long id(ResultActions result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
