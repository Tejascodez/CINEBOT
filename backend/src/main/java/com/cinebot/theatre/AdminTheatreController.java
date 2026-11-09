package com.cinebot.theatre;

import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.theatre.dto.TheatreDetailResponse;
import com.cinebot.theatre.dto.TheatreRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/theatres")
public class AdminTheatreController {

    private final TheatreService theatreService;

    public AdminTheatreController(TheatreService theatreService) {
        this.theatreService = theatreService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TheatreDetailResponse create(@Valid @RequestBody TheatreRequest request) {
        return theatreService.create(request);
    }

    @PutMapping("/{id}")
    public TheatreDetailResponse update(@PathVariable Long id, @Valid @RequestBody TheatreRequest request) {
        return theatreService.update(id, request);
    }

    @PostMapping("/{id}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenResponse addScreen(@PathVariable Long id, @Valid @RequestBody ScreenRequest request) {
        return theatreService.addScreen(id, request);
    }
}
