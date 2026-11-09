package com.cinebot.booking.dto;

import com.cinebot.booking.BookingStatus;
import com.cinebot.screen.SeatType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;


@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BookingResponse(
        Long id,
        String reference,
        BookingStatus status,
        ShowInfo show,
        List<SeatInfo> seats,
        long totalPaise,
        String currency,
        UUID seatLockId,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt,
        Ticket ticket) {

    /** Present only while CONFIRMED. qrPayload is rendered as a QR code by the client. */
    public record Ticket(String qrPayload) {
    }

    public record ShowInfo(Long showId, Long movieId, String movieTitle, String theatreName, String city,
                           String screenName, OffsetDateTime startTime) {
    }

    public record SeatInfo(Long showSeatId, String label, SeatType type, long pricePaise) {
    }
}
