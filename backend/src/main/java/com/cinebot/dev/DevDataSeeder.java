package com.cinebot.dev;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.movie.MovieService;
import com.cinebot.movie.MovieSort;
import com.cinebot.movie.dto.MovieListQuery;
import com.cinebot.movie.dto.MovieSummaryResponse;
import com.cinebot.screen.ScreenService;
import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.screen.dto.SeatLayoutRequest;
import com.cinebot.screen.dto.SeatLayoutRequest.Row;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.CreateShowRequest;
import com.cinebot.theatre.TheatreService;
import com.cinebot.theatre.dto.TheatreDetailResponse;
import com.cinebot.theatre.dto.TheatreRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DEV ONLY. Creates sample theatres/screens/layouts once, and on every start (and nightly) tops up shows for the next
 * 3 days, so there is always something to book locally. Goes through the real services, so seeded data obeys
 * the same validation and DB constraints as the admin API (overlaps on restart are simply skipped).
 */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final int DAYS_AHEAD = 3;
    private static final List<LocalTime> SLOTS =
            List.of(LocalTime.of(9, 30), LocalTime.of(13, 15), LocalTime.of(17, 0), LocalTime.of(20, 45));
    private static final Map<SeatType, Long> PRICES =
            Map.of(SeatType.REGULAR, 20_000L, SeatType.PREMIUM, 30_000L, SeatType.RECLINER, 50_000L);

    private final TheatreService theatres;
    private final ScreenService screens;
    private final ShowService shows;
    private final MovieService movies;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DevDataSeeder(TheatreService theatres, ScreenService screens, ShowService shows, MovieService movies,
                  JdbcTemplate jdbc, Clock clock) {
        this.theatres = theatres;
        this.screens = screens;
        this.shows = shows;
        this.movies = movies;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT count(*) FROM theatres", Integer.class) == 0) {
            seedVenues();
        }
        seedShows();
    }

    /**
     * A dev server left running for days would otherwise run out of shows (they are only created on start):
     * every night just after midnight IST, add the new third day.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Kolkata")
    void topUpShowsDaily() {
        seedShows();
    }

    private boolean slotTaken(Long screenId, OffsetDateTime start) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shows WHERE screen_id = ? AND start_time = ?)",
                Boolean.class, screenId, start));
    }

    private void seedVenues() {
        venue("PVR Velachery", "PVR", "Chennai", "Phoenix MarketCity, Velachery Main Rd", "12.991700", "80.217500", 2);
        venue("INOX Anna Nagar", "INOX", "Chennai", "2nd Avenue, Anna Nagar", "13.085000", "80.210100", 1);
        venue("PVR Lower Parel", "PVR", "Mumbai", "Phoenix Palladium, Lower Parel", "18.994000", "72.825000", 2);
        venue("INOX Garuda Mall", "INOX", "Bengaluru", "Magrath Rd, Ashok Nagar", "12.970000", "77.609500", 1);
        log.info("Dev seed: theatres, screens and seat layouts created");
    }

    private void venue(String name, String brand, String city, String address, String lat, String lng, int screenCount) {
        TheatreDetailResponse theatre = theatres.create(new TheatreRequest(name, brand, city, address,
                new BigDecimal(lat), new BigDecimal(lng), true));
        for (int i = 1; i <= screenCount; i++) {
            ScreenResponse screen = theatres.addScreen(theatre.id(),
                    new ScreenRequest("Screen " + i, i == 1 ? ScreenType.STANDARD : ScreenType.DOLBY));
            screens.replaceLayout(screen.id(), new SeatLayoutRequest(List.of(
                    new Row("A", 12, SeatType.REGULAR), new Row("B", 12, SeatType.REGULAR),
                    new Row("C", 12, SeatType.REGULAR), new Row("D", 12, SeatType.REGULAR),
                    new Row("E", 12, SeatType.REGULAR), new Row("F", 12, SeatType.PREMIUM),
                    new Row("G", 12, SeatType.PREMIUM), new Row("H", 8, SeatType.RECLINER))));
        }
    }

    private void seedShows() {
        List<Long> movieIds = movies.list(new MovieListQuery(null, null, null, MovieSort.POPULARITY, 0, 50))
                .content().stream().map(MovieSummaryResponse::id).toList();
        List<Long> screenIds = jdbc.queryForList("SELECT id FROM screens ORDER BY id", Long.class);
        if (movieIds.isEmpty() || screenIds.isEmpty()) {
            return;
        }
        LocalDate today = BusinessTime.today(clock);
        OffsetDateTime earliest = OffsetDateTime.now(clock).plusMinutes(30);
        int created = 0;
        List<String> skipped = new ArrayList<>();
        for (int day = 0; day < DAYS_AHEAD; day++) {
            LocalDate date = today.plusDays(day);
            for (int s = 0; s < screenIds.size(); s++) {
                for (int slot = 0; slot < SLOTS.size(); slot++) {
                    OffsetDateTime start = date.atTime(SLOTS.get(slot)).atZone(BusinessTime.ZONE).toOffsetDateTime();
                    if (start.isBefore(earliest) || slotTaken(screenIds.get(s), start)) {
                        continue; // the overlap constraint would reject it anyway; skipping avoids ERROR log noise
                    }
                    Long movieId = movieIds.get((s * SLOTS.size() + slot + day) % movieIds.size());
                    try {
                        shows.create(new CreateShowRequest(movieId, screenIds.get(s), start, null, "2D", PRICES));
                        created++;
                    } catch (ApiException e) {
                        if (e.code() != ErrorCode.SHOW_OVERLAP) {
                            skipped.add(e.code().name());
                        }
                    }
                }
            }
        }
        log.info("Dev seed: {} shows created for the next {} days{}", created, DAYS_AHEAD,
                skipped.isEmpty() ? "" : " (skipped: " + skipped + ")");
    }
}
