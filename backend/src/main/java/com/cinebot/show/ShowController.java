package com.cinebot.show;

import com.cinebot.seat.dto.SeatMapResponse;
import com.cinebot.show.dto.ShowResponse;
import com.cinebot.show.dto.ShowSearchQuery;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /** Bounded by one day and at most 200 results, so no pagination. */
    @GetMapping
    public List<ShowResponse> search(@Valid @ModelAttribute ShowSearchQuery query) {
        return showService.search(query);
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable Long id) {
        return showService.get(id);
    }

    @GetMapping("/{id}/seats")
    public SeatMapResponse seats(@PathVariable Long id) {
        return showService.seatMap(id);
    }
}
