package com.cinebot.screen;

import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.screen.dto.SeatLayoutRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Screens are created under their theatre (POST /admin/theatres/{id}/screens); layout is managed here. */
@RestController
@RequestMapping("/api/v1/admin/screens")
public class AdminScreenController {

    private final ScreenService screenService;

    public AdminScreenController(ScreenService screenService) {
        this.screenService = screenService;
    }

    @GetMapping("/{id}")
    public ScreenResponse get(@PathVariable Long id) {
        return screenService.get(id);
    }

    @PutMapping("/{id}/layout")
    public ScreenResponse replaceLayout(@PathVariable Long id, @Valid @RequestBody SeatLayoutRequest request) {
        return screenService.replaceLayout(id, request);
    }
}
