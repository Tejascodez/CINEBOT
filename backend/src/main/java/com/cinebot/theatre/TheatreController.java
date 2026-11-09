package com.cinebot.theatre;

import com.cinebot.common.web.PageResponse;
import com.cinebot.theatre.dto.TheatreDetailResponse;
import com.cinebot.theatre.dto.TheatreListQuery;
import com.cinebot.theatre.dto.TheatreSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/theatres")
public class TheatreController {

    private final TheatreService theatreService;

    public TheatreController(TheatreService theatreService) {
        this.theatreService = theatreService;
    }

    /** GET /api/v1/theatres?city=Chennai&brand=PVR */
    @GetMapping
    public PageResponse<TheatreSummaryResponse> list(@Valid @ModelAttribute TheatreListQuery query) {
        return theatreService.listByCity(query);
    }

    @GetMapping("/{id}")
    public TheatreDetailResponse get(@PathVariable Long id) {
        return theatreService.getPublic(id);
    }
}
