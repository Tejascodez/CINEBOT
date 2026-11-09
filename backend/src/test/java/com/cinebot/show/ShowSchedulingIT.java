package com.cinebot.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.screen.SeatType;
import com.cinebot.show.dto.CreateShowRequest;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.support.VenueTestSupport;
import com.cinebot.theatre.dto.TheatreRequest;
import com.cinebot.user.Role;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Scheduling rules: inventory generation, overlap prevention (incl. under concurrency), pricing, cancellation. */
class ShowSchedulingIT extends VenueTestSupport {

    private static final java.time.format.DateTimeFormatter ISO = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private com.cinebot.booking.lock.SeatLockService seatLocks;

    private long movie;
    private long theatre;
    private long screen;

    @BeforeEach
    void setUp() {
        movie = movie("Interstellar", 160);       // occupies screen 160 + 20 = 180 min
        theatre = theatre("PVR Velachery", "Chennai");
        screen = screenWithLayout(theatre, "Screen 1");
    }

    @Test
    void createsShowWithFullPricedInventory() throws Exception {
        String body = """
                {"movieId":%d,"screenId":%d,"startTime":"%s","format":"3D",
                 "prices":{"REGULAR":20000,"PREMIUM":35000}}""".formatted(movie, screen, ist(tomorrow(), 18, 0));

        mvc.perform(post("/api/v1/admin/shows").header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.format").value("3D"))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.startTime").value(ISO.format(ist(tomorrow(), 18, 0))))
                .andExpect(jsonPath("$.endTime").value(ISO.format(ist(tomorrow(), 21, 0))))
                .andExpect(jsonPath("$.totalSeats").value(5))
                .andExpect(jsonPath("$.availableSeats").value(5))
                .andExpect(jsonPath("$.minPricePaise").value(20000))
                .andExpect(jsonPath("$.prices[0].seatType").value("REGULAR"))
                .andExpect(jsonPath("$.prices[1].pricePaise").value(35000));

        assertThat(jdbc.queryForList("""
                SELECT se.seat_type || ':' || ss.price_paise FROM show_seats ss JOIN seats se ON se.id = ss.seat_id
                ORDER BY se.row_index, se.seat_number""", String.class))
                .containsExactly("REGULAR:20000", "REGULAR:20000", "REGULAR:20000", "PREMIUM:35000", "PREMIUM:35000");
    }

    @Test
    void overlappingShowOnSameScreenIsRejected() {
        show(movie, screen, ist(tomorrow(), 18, 0)); // screen busy 18:00-21:00

        assertCode(() -> show(movie, screen, ist(tomorrow(), 20, 59)), ErrorCode.SHOW_OVERLAP);
        assertCode(() -> show(movie, screen, ist(tomorrow(), 15, 1)), ErrorCode.SHOW_OVERLAP); // ends 18:01
        assertThat(count("shows")).isEqualTo(1);
        assertThat(count("show_seats")).isEqualTo(5); // the rejected shows left no inventory behind
    }

    @Test
    void backToBackShowsAndOtherScreensAreAllowed() {
        show(movie, screen, ist(tomorrow(), 18, 0));

        show(movie, screen, ist(tomorrow(), 21, 0));   // starts exactly when the previous one frees the screen
        show(movie, screen, ist(tomorrow(), 15, 0));   // ends exactly at 18:00
        show(movie, screenWithLayout(theatre, "Screen 2"), ist(tomorrow(), 18, 0));

        assertThat(count("shows")).isEqualTo(4);
    }

    @Test
    void cancelledShowFreesTheSlot() {
        ShowResponse first = show(movie, screen, ist(tomorrow(), 18, 0));
        showService.cancel(first.id());

        show(movie, screen, ist(tomorrow(), 18, 0));

        assertThat(count("shows")).isEqualTo(2);
    }

    /**
     * Many admins/instances schedule overlapping shows on one screen at the same moment. A "check then insert"
     * in Java would let several through; the exclusion constraint lets exactly one commit.
     */
    @Test
    void concurrentOverlappingSchedulingHasExactlyOneWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            OffsetDateTime time = ist(tomorrow(), 18, 0).plusMinutes(i * 10L); // 18:00, 18:10, ... all overlap
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    show(movie, screen, time);
                } catch (ApiException e) {
                    failures.add(e.code());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(count("shows")).isEqualTo(1);
        assertThat(failures).hasSize(threads - 1).containsOnly(ErrorCode.SHOW_OVERLAP);
        assertThat(count("show_seats")).isEqualTo(5);
    }

    @Test
    void everySeatTypeOnTheScreenNeedsAPrice() {
        assertCode(() -> showService.create(new CreateShowRequest(movie, screen, ist(tomorrow(), 18, 0), null, null,
                Map.of(SeatType.REGULAR, 20_000L))), ErrorCode.INVALID_PRICING);
    }

    @Test
    void schedulingPreconditions() {
        assertCode(() -> show(movie, screen, OffsetDateTime.now().minusMinutes(1)), ErrorCode.SHOW_IN_PAST);
        assertCode(() -> show(movie("Unknown runtime", null), screen, ist(tomorrow(), 18, 0)),
                ErrorCode.MOVIE_RUNTIME_UNKNOWN);
        long emptyScreen = theatreService.addScreen(theatre,
                new com.cinebot.screen.dto.ScreenRequest("Empty", com.cinebot.screen.ScreenType.STANDARD)).id();
        assertCode(() -> show(movie, emptyScreen, ist(tomorrow(), 18, 0)), ErrorCode.SCREEN_HAS_NO_SEATS);
        assertCode(() -> show(987654L, screen, ist(tomorrow(), 18, 0)), ErrorCode.MOVIE_NOT_FOUND);
        assertCode(() -> show(movie, 987654L, ist(tomorrow(), 18, 0)), ErrorCode.SCREEN_NOT_FOUND);

        theatreService.update(theatre, new TheatreRequest("PVR Velachery", "PVR", "Chennai", "x", null, null, false));
        assertCode(() -> show(movie, screen, ist(tomorrow(), 18, 0)), ErrorCode.THEATRE_INACTIVE);
    }

    @Test
    void startTimeWithoutOffsetIsRejected() throws Exception {
        String body = """
                {"movieId":%d,"screenId":%d,"startTime":"2030-01-01T18:00:00","prices":{"REGULAR":1,"PREMIUM":1}}"""
                .formatted(movie, screen);
        mvc.perform(post("/api/v1/admin/shows").header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void layoutIsFrozenOnceAShowExists() throws Exception {
        show(movie, screen, ist(tomorrow(), 18, 0));

        mvc.perform(put("/api/v1/admin/screens/" + screen + "/layout").header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"label\":\"A\",\"seats\":4,\"type\":\"REGULAR\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREEN_HAS_SHOWS"));
        assertThat(count("seats")).isEqualTo(5);
    }

    @Test
    void cancelBlocksRemainingSeatsAndIsIdempotent() {
        ShowResponse s = show(movie, screen, ist(tomorrow(), 18, 0));

        ShowResponse cancelled = showService.cancel(s.id());
        ShowResponse again = showService.cancel(s.id());

        assertThat(cancelled.status()).isEqualTo(ShowStatus.CANCELLED);
        assertThat(again.status()).isEqualTo(ShowStatus.CANCELLED);
        assertThat(cancelled.availableSeats()).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM show_seats", String.class)).containsExactly("BLOCKED");
    }

    @Test
    void cannotCancelShowWithHeldOrSoldSeats() {
        ShowResponse s = show(movie, screen, ist(tomorrow(), 18, 0));
        seatLocks.lock(user("holder"), new LockSeatsRequest(s.id(), List.of(seat(s.id(), "A1"))));

        assertCode(() -> showService.cancel(s.id()), ErrorCode.SHOW_HAS_BOOKINGS);
        assertThat(showService.get(s.id()).status()).isEqualTo(ShowStatus.SCHEDULED);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(code);
    }
}
