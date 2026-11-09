package com.cinebot.seat.dto;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.ShowSeatStatus;
import java.util.List;

public record SeatMapResponse(Long showId, int totalSeats, int availableSeats, List<Row> rows) {

    public record Row(String label, List<SeatView> seats) {
    }

    /** showSeatId is what clients send back when locking (Step 4); label is for humans and the chatbot. */
    public record SeatView(Long showSeatId, String label, int number, SeatType type, long pricePaise,
                           ShowSeatStatus status) {
    }
}
