package com.cinebot.booking.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.BookingService;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.screen.ScreenService;
import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.SeatLayoutRequest;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.CreateShowRequest;
import com.cinebot.support.PostgresTestContainer;
import com.cinebot.support.Race;
import com.cinebot.theatre.TheatreService;
import com.cinebot.theatre.dto.TheatreRequest;
import com.cinebot.user.Role;
import com.cinebot.user.UserService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Redis is unreachable for the whole test (nothing listens on port 1). Booking must remain correct:
 * the gate fails open and PostgreSQL alone decides. Separate context: only Postgres is started.
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RedisOutageIT {

    @Autowired
    private SeatLockService seatLocks;
    @Autowired
    private BookingService bookings;
    @Autowired
    private TheatreService theatres;
    @Autowired
    private ScreenService screens;
    @Autowired
    private ShowService shows;
    @Autowired
    private UserService users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @Test
    void bookingStaysCorrectWithoutRedis() throws Exception {
        long show = createShow();
        long a1 = jdbc.queryForObject("SELECT min(id) FROM show_seats WHERE show_id = ?", Long.class, show);

        List<Callable<SeatLockResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            AuthenticatedUser u = user("outage" + i);
            attempts.add(() -> seatLocks.lock(u, new LockSeatsRequest(show, List.of(a1))));
        }
        var outcomes = Race.run(attempts);

        assertThat(outcomes.stream().filter(Race.Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((ApiException) o.error()).code()))
                .containsOnly(ErrorCode.SEATS_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM show_seats WHERE status = 'LOCKED'", Integer.class))
                .isEqualTo(1);

        // the full flow still works: winner books, then cancels
        var winner = outcomes.stream().filter(Race.Outcome::succeeded).findFirst().orElseThrow().value();
        Long ownerId = jdbc.queryForObject("SELECT user_id FROM seat_locks WHERE id = ?", Long.class, winner.lockId());
        AuthenticatedUser owner = new AuthenticatedUser(ownerId, "x", Role.USER);
        var booking = bookings.create(owner, winner.lockId()).booking();
        bookings.cancel(owner, booking.id());
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE id = ?", String.class, a1))
                .isEqualTo("AVAILABLE");
    }

    /** DB-only path under overlapping multi-seat contention: all-or-nothing, no deadlocks, no double ownership. */
    @Test
    void overlappingMultiSeatRequestsWithoutRedis() throws Exception {
        long show = createShow();
        List<Long> seats = jdbc.queryForList("SELECT id FROM show_seats WHERE show_id = ? ORDER BY id", Long.class, show);
        java.util.Random random = new java.util.Random(7);
        List<List<Long>> requested = new ArrayList<>();
        List<Callable<SeatLockResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Long> pick = new ArrayList<>(seats);
            java.util.Collections.shuffle(pick, random);
            List<Long> want = pick.subList(0, 2 + random.nextInt(2));
            requested.add(want);
            AuthenticatedUser u = user("overlap" + i);
            attempts.add(() -> seatLocks.lock(u, new LockSeatsRequest(show, want)));
        }

        var outcomes = Race.run(attempts);

        for (int i = 0; i < outcomes.size(); i++) {
            var o = outcomes.get(i);
            if (o.succeeded()) {
                assertThat(jdbc.queryForList("SELECT id FROM show_seats WHERE lock_id = ?", Long.class, o.value().lockId()))
                        .containsExactlyInAnyOrderElementsOf(requested.get(i));
            } else {
                assertThat(o.error()).as("attempt %d", i).isInstanceOf(ApiException.class);
                assertThat(((ApiException) o.error()).code()).isEqualTo(ErrorCode.SEATS_UNAVAILABLE);
            }
        }
    }

    @Test
    void readinessStaysUpWhileRedisIsDown() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private long createShow() {
        long movie = jdbc.queryForObject("""
                INSERT INTO movies (tmdb_id, title, runtime_minutes, popularity) VALUES (1, 'M', 100, 1)
                ON CONFLICT (tmdb_id) DO UPDATE SET title = 'M' RETURNING id""", Long.class);
        long theatre = theatres.create(new TheatreRequest("T" + System.nanoTime(), null, "Chennai", "x", null, null, true)).id();
        long screen = theatres.addScreen(theatre, new ScreenRequest("S", ScreenType.STANDARD)).id();
        screens.replaceLayout(screen, new SeatLayoutRequest(List.of(new SeatLayoutRequest.Row("A", 5, SeatType.REGULAR))));
        var start = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).atTime(18, 0)
                .atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
        return shows.create(new CreateShowRequest(movie, screen, start, null, null, Map.of(SeatType.REGULAR, 100L))).id();
    }

    private AuthenticatedUser user(String name) {
        var u = users.createUser(name + System.nanoTime() + "@t.dev", "x", name, null, Role.USER);
        return new AuthenticatedUser(u.getId(), u.getEmail(), Role.USER);
    }
}
