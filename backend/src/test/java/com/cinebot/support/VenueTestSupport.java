package com.cinebot.support;

import com.cinebot.common.time.BusinessTime;
import com.cinebot.screen.ScreenService;
import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.SeatLayoutRequest;
import com.cinebot.screen.dto.SeatLayoutRequest.Row;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.CreateShowRequest;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.theatre.TheatreService;
import com.cinebot.theatre.dto.TheatreRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/** Fixtures for theatre/screen/show tests, built through the real services. */
public abstract class VenueTestSupport extends IntegrationTest {

    /** Small screen: row A = 3 REGULAR, row B = 2 PREMIUM -> 5 seats. */
    protected static final List<Row> SMALL_LAYOUT =
            List.of(new Row("A", 3, SeatType.REGULAR), new Row("B", 2, SeatType.PREMIUM));
    protected static final Map<SeatType, Long> PRICES = Map.of(SeatType.REGULAR, 20_000L, SeatType.PREMIUM, 35_000L);

    @Autowired
    protected TheatreService theatreService;
    @Autowired
    protected ScreenService screenService;
    @Autowired
    protected ShowService showService;

    protected com.cinebot.auth.AuthenticatedUser user(String name) {
        var u = userService.createUser(name + System.nanoTime() + "@test.dev", "x", name, null,
                com.cinebot.user.Role.USER);
        return new com.cinebot.auth.AuthenticatedUser(u.getId(), u.getEmail(), u.getRole());
    }

    protected String bearerFor(com.cinebot.auth.AuthenticatedUser user) {
        return "Bearer " + jwtService.generateAccessToken(userService.getById(user.id()));
    }

    /** show_seat id for a seat label such as "A1". */
    protected long seat(long showId, String label) {
        return jdbc.queryForObject("""
                SELECT ss.id FROM show_seats ss JOIN seats se ON se.id = ss.seat_id
                WHERE ss.show_id = ? AND se.row_label || se.seat_number = ?""", Long.class, showId, label);
    }

    protected List<Long> allSeats(long showId) {
        return jdbc.queryForList("SELECT id FROM show_seats WHERE show_id = ? ORDER BY id", Long.class, showId);
    }

    protected long movie(String title, Integer runtimeMinutes) {
        return jdbc.queryForObject("""
                INSERT INTO movies (tmdb_id, title, original_language, runtime_minutes, popularity)
                VALUES ((SELECT coalesce(max(tmdb_id), 0) + 1 FROM movies), ?, 'en', ?, 10) RETURNING id
                """, Long.class, title, runtimeMinutes);
    }

    protected long theatre(String name, String city) {
        return theatreService.create(new TheatreRequest(name, "PVR", city, "Some address", null, null, true)).id();
    }

    protected long screenWithLayout(long theatreId, String name) {
        long screenId = theatreService.addScreen(theatreId, new ScreenRequest(name, ScreenType.STANDARD)).id();
        screenService.replaceLayout(screenId, new SeatLayoutRequest(SMALL_LAYOUT));
        return screenId;
    }

    protected ShowResponse show(long movieId, long screenId, OffsetDateTime start) {
        return showService.create(new CreateShowRequest(movieId, screenId, start, null, null, PRICES));
    }

    protected static LocalDate tomorrow() {
        return BusinessTime.today(Clock.systemUTC()).plusDays(1);
    }

    /** A local IST time on the given date, as an offset date-time. */
    protected static OffsetDateTime ist(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute)).atZone(BusinessTime.ZONE).toOffsetDateTime();
    }
}
