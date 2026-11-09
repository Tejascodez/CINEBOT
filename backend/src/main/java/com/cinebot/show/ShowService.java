package com.cinebot.show;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.persistence.Constraints;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.movie.Movie;
import com.cinebot.movie.MovieService;
import com.cinebot.screen.Screen;
import com.cinebot.screen.ScreenService;
import com.cinebot.screen.SeatType;
import com.cinebot.seat.ShowSeatService;
import com.cinebot.seat.dto.SeatMapResponse;
import com.cinebot.show.dto.CreateShowRequest;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.show.dto.ShowSearchQuery;
import com.cinebot.theatre.TheatreService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    /** Ads/trailers + cleaning between shows; part of the interval the screen is occupied. */
    static final Duration TURNAROUND = Duration.ofMinutes(20);
    static final String OVERLAP_CONSTRAINT = "ex_shows_screen_no_overlap";

    private final ShowRepository shows;
    private final ShowQueryRepository showQueries;
    private final MovieService movieService;
    private final ScreenService screenService;
    private final TheatreService theatreService;
    private final ShowSeatService showSeatService;
    private final Clock clock;

    public ShowService(ShowRepository shows, ShowQueryRepository showQueries, MovieService movieService,
                       ScreenService screenService, TheatreService theatreService, ShowSeatService showSeatService,
                       Clock clock) {
        this.shows = shows;
        this.showQueries = showQueries;
        this.movieService = movieService;
        this.screenService = screenService;
        this.theatreService = theatreService;
        this.showSeatService = showSeatService;
        this.clock = clock;
    }

    /**
     * Creates the show AND its seat inventory atomically: one transaction, so a show can never be visible
     * without seats (or half its seats).
     */
    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        Instant start = request.startTime().toInstant();
        if (!start.isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.SHOW_IN_PAST);
        }
        Movie movie = movieService.requireMovie(request.movieId());
        if (movie.getRuntimeMinutes() == null) {
            throw new ApiException(ErrorCode.MOVIE_RUNTIME_UNKNOWN,
                    "Movie " + movie.getId() + " has no runtime; it cannot be scheduled");
        }
        Screen screen = screenService.getEntity(request.screenId());
        theatreService.requireActive(screen.getTheatreId());

        Set<SeatType> seatTypes = screenService.seatTypesOf(screen.getId());
        if (seatTypes.isEmpty()) {
            throw new ApiException(ErrorCode.SCREEN_HAS_NO_SEATS);
        }
        Set<SeatType> missing = new TreeSet<>(seatTypes);
        missing.removeAll(request.prices().keySet());
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_PRICING, "Missing prices for seat types " + missing);
        }

        Instant end = start.plus(Duration.ofMinutes(movie.getRuntimeMinutes())).plus(TURNAROUND);
        String language = request.language() != null ? request.language() : movie.getOriginalLanguage();
        Show show = new Show(movie, screen, start, end, language == null ? "en" : language,
                request.format() == null ? "2D" : request.format());
        try {
            shows.saveAndFlush(show);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolation(e, OVERLAP_CONSTRAINT)) {
                throw new ApiException(ErrorCode.SHOW_OVERLAP,
                        "Screen " + screen.getId() + " is occupied between " + BusinessTime.toLocal(start)
                                + " and " + BusinessTime.toLocal(end));
            }
            throw e;
        }
        int seats = showSeatService.createInventory(show.getId(), screen.getId(), request.prices());
        log.info("Scheduled show {} (movie {}, screen {}) with {} seats", show.getId(), movie.getId(), screen.getId(), seats);
        return get(show.getId());
    }

    /**
     * Cancels a show that has no held or sold seats; remaining seats become BLOCKED (unsellable).
     * The show row lock serialises concurrent cancels. Cancelling shows with bookings needs refunds (Step 5).
     */
    @Transactional
    public ShowResponse cancel(Long showId) {
        Show show = shows.findByIdForUpdate(showId).orElseThrow(() -> notFound(showId));
        if (show.getStatus() == ShowStatus.SCHEDULED) {
            if (showSeatService.hasHeldOrSoldSeats(showId)) {
                throw new ApiException(ErrorCode.SHOW_HAS_BOOKINGS);
            }
            show.cancel();
            shows.flush();
            showSeatService.blockAllAvailable(showId);
        }
        return get(showId);
    }

    /**
     * Called inside the seat-lock transaction. Without the shared row lock, this race is possible:
     * cancel checks "no LOCKED seats" -> a lock attempt (started before the cancel committed) locks seats ->
     * cancel commits: a cancelled show with held seats. With FOR SHARE vs FOR UPDATE the two serialise.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireBookableForSeatLock(Long showId) {
        Show show = shows.findByIdForShare(showId).orElseThrow(() -> notFound(showId));
        if (show.getStatus() != ShowStatus.SCHEDULED) {
            throw new ApiException(ErrorCode.SHOW_NOT_BOOKABLE, "Show " + showId + " was cancelled");
        }
        if (!show.getStartTime().isAfter(clock.instant())) {
            throw new ApiException(ErrorCode.SHOW_NOT_BOOKABLE, "Show " + showId + " has already started");
        }
    }

    @Transactional(readOnly = true)
    public java.util.Map<Long, ShowResponse> getMany(java.util.Collection<Long> showIds) {
        return showQueries.findByIds(showIds);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(Long showId) {
        return showQueries.findById(showId).orElseThrow(() -> notFound(showId));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long showId) {
        if (!shows.existsById(showId)) {
            throw notFound(showId);
        }
        return showSeatService.seatMap(showId);
    }

    /**
     * Resolves the business-local date/time window to UTC instants. Shows that already started are excluded,
     * so "today" never offers a show you can no longer attend.
     */
    @Transactional(readOnly = true)
    public List<ShowResponse> search(ShowSearchQuery query) {
        LocalDate date = query.date() != null ? query.date() : BusinessTime.today(clock);
        Instant from = (query.from() != null ? date.atTime(query.from()) : date.atStartOfDay())
                .atZone(BusinessTime.ZONE).toInstant();
        Instant to = (query.to() != null ? date.atTime(query.to()) : date.plusDays(1).atStartOfDay())
                .atZone(BusinessTime.ZONE).toInstant();
        Instant now = clock.instant();
        if (from.isBefore(now)) {
            from = now;
        }
        if (!from.isBefore(to)) {
            return List.of();
        }
        String city = query.city() == null || query.city().isBlank() ? null : query.city().trim();
        return showQueries.search(query.movieId(), query.theatreId(), city, from, to);
    }

    private static ApiException notFound(Long showId) {
        return new ApiException(ErrorCode.SHOW_NOT_FOUND, "Show " + showId + " not found");
    }
}
