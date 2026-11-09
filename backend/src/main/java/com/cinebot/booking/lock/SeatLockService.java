package com.cinebot.booking.lock;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.BookingProperties;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Seat-lock workflow = Redis admission gate (fast, best effort) + PostgreSQL transaction (authoritative).
 * Not @Transactional itself: Redis must be asked before the DB transaction starts, and cleaned up if it fails.
 */
@Service
public class SeatLockService {

    private final RedisSeatLockGate gate;
    private final SeatLockWriter writer;
    private final BookingProperties properties;
    private final Clock clock;

    public SeatLockService(RedisSeatLockGate gate, SeatLockWriter writer, BookingProperties properties, Clock clock) {
        this.gate = gate;
        this.writer = writer;
        this.properties = properties;
        this.clock = clock;
    }

    public SeatLockResponse lock(AuthenticatedUser user, LockSeatsRequest request) {
        List<Long> seatIds = request.showSeatIds().stream().sorted().toList();
        if (seatIds.stream().distinct().count() != seatIds.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "showSeatIds contains duplicates");
        }
        if (seatIds.size() > properties.maxSeatsPerLock()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "At most " + properties.maxSeatsPerLock() + " seats per booking");
        }
        UUID lockId = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.lockTtl());

        RedisSeatLockGate.Result gated = gate.tryAcquire(request.showId(), seatIds, lockId, properties.lockTtl());
        if (gated.outcome() == RedisSeatLockGate.Outcome.CONFLICT) {
            // Rejected without touching PostgreSQL.
            throw new ApiException(ErrorCode.SEATS_UNAVAILABLE, "Some selected seats are being booked by someone else",
                    Map.of("unavailableSeatIds", gated.conflictingSeatIds()));
        }
        try {
            return writer.acquire(lockId, user.id(), request.showId(), seatIds, now, expiresAt);
        } catch (RuntimeException e) {
            gate.release(request.showId(), seatIds, lockId); // DB said no (or failed): drop our Redis claim
            throw e;
        }
    }

    public void release(AuthenticatedUser user, UUID lockId) {
        writer.release(user.id(), lockId, clock.instant());
    }
}
