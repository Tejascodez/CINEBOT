package com.cinebot.agent.tools;

import com.cinebot.common.time.BusinessTime;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.show.dto.ShowSearchQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Showtimes. Backs GET /api/v1/shows?movieId&theatreId&city&date&from&to (same service method). */
@Component
public class ShowtimeTools {

    static final int MAX_RESULTS = 20;

    record ShowItem(Long showId, String movie, Long movieId, String theatre, Long theatreId, String screen,
                    String date, String time, String format, String language, int availableSeats, String fromPrice) {
    }

    private final ShowService shows;
    private final Clock clock;

    public ShowtimeTools(ShowService shows, Clock clock) {
        this.shows = shows;
        this.clock = clock;
    }

    @Tool(name = "findShowtimes", description = """
            Find upcoming showtimes (India time) for a movie and/or a theatre and/or a city, on one date, \
            optionally within a time window. Use it for questions like 'when is Interstellar playing', \
            'shows at PVR tomorrow', 'something tonight after 8'. At least one of movieId, theatreId or city is \
            required. Words like 'tomorrow' or 'tonight' must be converted to an ISO date using today's date. \
            Returns showIds for the seat tools.""")
    public ToolReply findShowtimes(
            @ToolParam(required = false, description = "movieId from searchMovies") Long movieId,
            @ToolParam(required = false, description = "theatreId from findTheatres") Long theatreId,
            @ToolParam(required = false, description = "City name") String city,
            @ToolParam(required = false, description = "Date YYYY-MM-DD; defaults to today") String date,
            @ToolParam(required = false, description = "Earliest start time HH:mm (24h), e.g. 18:00 for 'evening'") String after,
            @ToolParam(required = false, description = "Latest start time HH:mm (24h)") String before,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        Long movie = ToolArgs.optionalId(movieId, "movieId");
        Long theatre = ToolArgs.optionalId(theatreId, "theatreId");
        String cityName = ToolArgs.optionalText(city, "city", 100);
        if (movie == null && theatre == null && cityName == null) {
            throw new InvalidToolArgumentException("provide movieId, theatreId or city (ask the user for the city)");
        }
        LocalDate day = ToolArgs.optionalDate(date, "date");
        LocalDate today = BusinessTime.today(clock);
        if (day != null && day.isBefore(today)) {
            throw new InvalidToolArgumentException("date " + day + " is in the past; today is " + today);
        }
        LocalTime from = ToolArgs.optionalTime(after, "after");
        LocalTime to = ToolArgs.optionalTime(before, "before");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidToolArgumentException("after must be earlier than before");
        }

        List<ShowResponse> found = shows.search(new ShowSearchQuery(movie, theatre, cityName, day, from, to));

        if (cityName != null) {
            turn.state().selectCity(cityName);
        }
        turn.state().setDate((day == null ? today : day).toString());
        if (found.isEmpty()) {
            return ToolReply.error("NO_SHOWS_FOUND", "No upcoming shows match on " + (day == null ? today : day)
                    + ". Suggest another date, time or theatre.");
        }
        if (found.stream().map(ShowResponse::movieId).distinct().count() == 1) {
            turn.state().selectMovie(found.getFirst().movieId(), found.getFirst().movieTitle());
        }
        if (theatre != null) {
            turn.state().selectTheatre(theatre, found.getFirst().theatreName());
        }
        List<ShowResponse> shown = found.stream().limit(MAX_RESULTS).toList();
        turn.show(new AgentCard(AgentCard.Type.SHOWS, shown));
        List<ShowItem> items = shown.stream().map(s -> new ShowItem(s.id(), s.movieTitle(), s.movieId(),
                s.theatreName(), s.theatreId(), s.screenName(), Display.date(s.startTime()), Display.time(s.startTime()),
                s.format(), s.language(), s.availableSeats(),
                s.minPricePaise() == null ? null : Display.rupees(s.minPricePaise()))).toList();
        String summary = found.size() > MAX_RESULTS
                ? found.size() + " shows match; the first " + MAX_RESULTS + " are listed. Suggest narrowing down."
                : found.size() + " show(s) found.";
        return ToolReply.ok(summary, Map.of("shows", items));
    }
}
