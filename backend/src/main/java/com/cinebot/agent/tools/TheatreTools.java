package com.cinebot.agent.tools;

import com.cinebot.theatre.TheatreService;
import com.cinebot.theatre.dto.TheatreListQuery;
import com.cinebot.theatre.dto.TheatreSummaryResponse;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Theatres by location. Backs GET /api/v1/theatres?city=&brand= (same service method). */
@Component
public class TheatreTools {

    record TheatreItem(Long theatreId, String name, String brand, String address) {
    }

    private final TheatreService theatres;

    public TheatreTools(TheatreService theatres) {
        this.theatres = theatres;
    }

    @Tool(name = "findTheatres", description = """
            List cinemas (theatres) in a city, optionally of one brand such as PVR or INOX. Use it when the user \
            asks which theatres there are, or wants to choose a theatre before choosing a showtime. \
            Requires the city: if the user has not said one and none is in the conversation state, ask.""")
    public ToolReply findTheatres(
            @ToolParam(description = "City name, e.g. 'Chennai'") String city,
            @ToolParam(required = false, description = "Brand, e.g. 'PVR', 'INOX'") String brand,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        String cityName = ToolArgs.requireText(city, "city", 100);
        List<TheatreSummaryResponse> found = theatres.listByCity(
                new TheatreListQuery(cityName, ToolArgs.optionalText(brand, "brand", 50), 0, 20)).content();
        turn.state().selectCity(cityName);
        if (found.isEmpty()) {
            return ToolReply.error("NO_THEATRES_FOUND", "CineBot has no theatres in " + cityName
                    + ". Ask whether another city works.");
        }
        if (found.size() == 1) {
            turn.state().selectTheatre(found.getFirst().id(), found.getFirst().name());
        }
        turn.show(new AgentCard(AgentCard.Type.THEATRES, found));
        List<TheatreItem> items = found.stream()
                .map(t -> new TheatreItem(t.id(), t.name(), t.brand(), t.address())).toList();
        return ToolReply.ok(items.size() + " theatre(s) in " + cityName + ", in this order.", Map.of("theatres", items));
    }
}
