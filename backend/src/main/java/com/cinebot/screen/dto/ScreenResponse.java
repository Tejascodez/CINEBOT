package com.cinebot.screen.dto;

import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import java.util.List;

public record ScreenResponse(Long id, Long theatreId, String name, ScreenType screenType, int totalSeats,
                             List<RowSummary> rows) {

    public record RowSummary(String label, int seats, SeatType type) {
    }
}
