package com.cinebot.agent.tools;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.ShowSeatStatus;
import com.cinebot.seat.dto.SeatMapResponse;
import com.cinebot.show.ShowService;
import com.cinebot.show.dto.ShowResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Seat availability and layout. Backs GET /api/v1/shows/{id} and /shows/{id}/seats (same service methods).
 * Availability comes ONLY from here (PostgreSQL, live), never from the model's memory, and is checked AGAIN by
 * the booking engine when seats are actually held.
 */
@Component
public class SeatTools {

    static final int MAX_TICKETS = 10; // = cinebot.booking max seats per lock

    record TypeAvailability(SeatType seatType, String price, int available) {
    }

    record RowLayout(String row, String seatType, String price, List<String> available, List<String> unavailable) {
    }

    private final ShowService shows;

    public SeatTools(ShowService shows) {
        this.shows = shows;
    }

    @Tool(name = "checkSeatAvailability", description = """
            Check how many seats are free right now for a show, by seat type and price. If the user said how many \
            tickets they want, pass ticketCount to also get the best available adjacent seats as a suggestion. \
            Use it after the user has picked a showtime, before preparing a booking.""")
    public ToolReply checkSeatAvailability(
            @ToolParam(description = "showId from findShowtimes") Long showId,
            @ToolParam(required = false, description = "Number of tickets wanted, 1-10") Integer ticketCount,
            @ToolParam(required = false, description = "Preferred seat type: REGULAR, PREMIUM or RECLINER") String seatType,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        ShowResponse show = ShowChecks.requireBookable(shows, ToolArgs.requireId(showId, "showId"), turn.now());
        Integer count = ToolArgs.optionalCount(ticketCount, "ticketCount", MAX_TICKETS);
        SeatType type = seatType(seatType);
        SeatMapResponse map = shows.seatMap(show.id());
        ShowChecks.remember(turn, show);
        if (count != null) {
            turn.state().setTicketCount(count);
        }

        Map<SeatType, TypeAvailability> byType = new LinkedHashMap<>();
        for (var row : map.rows()) {
            for (var seat : row.seats()) {
                var current = byType.get(seat.type());
                int free = (current == null ? 0 : current.available())
                        + (seat.status() == ShowSeatStatus.AVAILABLE ? 1 : 0);
                byType.put(seat.type(), new TypeAvailability(seat.type(), Display.rupees(seat.pricePaise()), free));
            }
        }
        turn.show(new AgentCard(AgentCard.Type.SEAT_MAP, Map.of("show", show, "seatMap", map)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("showId", show.id());
        data.put("show", ShowChecks.describe(show));
        data.put("availableSeats", map.availableSeats());
        data.put("byType", List.copyOf(byType.values()));
        if (map.availableSeats() == 0) {
            return ToolReply.error("SOLD_OUT", "This show is sold out. Offer other showtimes.");
        }
        if (count != null) {
            var suggestion = SeatSuggester.suggest(map, count, type);
            if (suggestion.isEmpty()) {
                return ToolReply.error("NOT_ENOUGH_SEATS", "Fewer than " + count + " suitable seats are free"
                        + (type == null ? "" : " of type " + type) + ". Offer another seat type or showtime.");
            }
            data.put("suggestedSeats", suggestion.get().labels());
            data.put("suggestedSeatsAdjacent", suggestion.get().adjacent());
            data.put("suggestedTotal", Display.rupees(suggestion.get().totalPaise()));
        }
        return ToolReply.ok(map.availableSeats() + " of " + map.totalSeats() + " seats are free right now "
                + "(availability can change until seats are held).", data);
    }

    @Tool(name = "getSeatLayout", description = """
            Get the seat layout of a show row by row (row A is nearest the screen), with free and taken seat \
            labels and prices. Use it when the user wants to choose specific seats or asks where seats are.""")
    public ToolReply getSeatLayout(
            @ToolParam(description = "showId from findShowtimes") Long showId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        ShowResponse show = ShowChecks.requireBookable(shows, ToolArgs.requireId(showId, "showId"), turn.now());
        SeatMapResponse map = shows.seatMap(show.id());
        ShowChecks.remember(turn, show);
        List<RowLayout> rows = new ArrayList<>();
        for (var row : map.rows()) {
            List<String> free = new ArrayList<>();
            List<String> taken = new ArrayList<>();
            row.seats().forEach(s -> (s.status() == ShowSeatStatus.AVAILABLE ? free : taken).add(s.label()));
            var types = row.seats().stream().map(SeatMapResponse.SeatView::type).distinct().toList();
            var prices = row.seats().stream().map(s -> Display.rupees(s.pricePaise())).distinct().toList();
            rows.add(new RowLayout(row.label(), types.size() == 1 ? types.getFirst().name() : "MIXED",
                    String.join("/", prices), free, taken));
        }
        turn.show(new AgentCard(AgentCard.Type.SEAT_MAP, Map.of("show", show, "seatMap", map)));
        return ToolReply.ok("Layout of " + map.totalSeats() + " seats, " + map.availableSeats() + " free.",
                Map.of("showId", show.id(), "show", ShowChecks.describe(show), "rows", rows));
    }

    private static SeatType seatType(String value) {
        String text = ToolArgs.optionalText(value, "seatType", 10);
        if (text == null) {
            return null;
        }
        try {
            return SeatType.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidToolArgumentException("seatType must be REGULAR, PREMIUM or RECLINER");
        }
    }
}
