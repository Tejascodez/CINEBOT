package com.cinebot.booking.lock.dto;

import com.cinebot.booking.lock.SeatLockStatus;
import com.cinebot.seat.SeatLine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SeatLockResponse(UUID lockId, Long showId, SeatLockStatus status, OffsetDateTime expiresAt,
                               List<SeatLine> seats, long totalPaise) {
}
