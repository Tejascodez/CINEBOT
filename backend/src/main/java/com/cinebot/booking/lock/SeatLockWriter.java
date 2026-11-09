package com.cinebot.booking.lock;

import com.cinebot.booking.Booking;
import com.cinebot.booking.lock.dto.SeatLockResponse;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.persistence.Constraints;
import com.cinebot.common.time.BusinessTime;
import com.cinebot.seat.SeatLine;
import com.cinebot.seat.ShowSeatInventory;
import com.cinebot.show.ShowService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The database half of locking: one short transaction per operation. PostgreSQL is the authority. */
@Component
class SeatLockWriter {

    private static final String ONE_ACTIVE_CONSTRAINT = "uk_seat_locks_one_active";

    private final SeatLockRepository locks;
    private final SeatLockLifecycle lifecycle;
    private final ShowSeatInventory inventory;
    private final ShowService showService;

    SeatLockWriter(SeatLockRepository locks, SeatLockLifecycle lifecycle, ShowSeatInventory inventory,
                   ShowService showService) {
        this.locks = locks;
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.showService = showService;
    }

    /**
     * 1. Show must be SCHEDULED and not started (FOR SHARE: serialises with cancel).
     * 2. Every seat must belong to the show.
     * 3. The user's previous lock for this show: expired -> closed now; still valid -> 409.
     * 4. Insert the lock row (unique partial index = one ACTIVE lock per user per show).
     * 5. Row-lock the seats (sorted, SKIP LOCKED: fail fast), then the conditional UPDATE (the authority).
     *    All seats or none.
     * Any failure throws, rolling back everything including the lock row.
     */
    @Transactional
    SeatLockResponse acquire(UUID lockId, Long userId, Long showId, List<Long> sortedSeatIds,
                             Instant now, Instant expiresAt) {
        showService.requireBookableForSeatLock(showId);

        List<SeatLine> seats = inventory.describe(showId, sortedSeatIds);
        if (seats.size() != sortedSeatIds.size()) {
            Set<Long> unknown = new HashSet<>(sortedSeatIds);
            seats.forEach(s -> unknown.remove(s.showSeatId()));
            throw new ApiException(ErrorCode.INVALID_SEAT_SELECTION,
                    "Seats " + unknown + " are not part of show " + showId, Map.of("invalidSeatIds", unknown));
        }

        locks.findActiveForUpdate(userId, showId).ifPresent(previous -> {
            if (previous.isExpired(now)) {
                lifecycle.expire(previous, now);
                // Hibernate's flush order runs INSERTs before UPDATEs. Without this flush, the new ACTIVE lock
                // would be inserted while the old row still says ACTIVE -> unique-index violation.
                locks.flush();
            } else {
                throw new ApiException(ErrorCode.ACTIVE_LOCK_EXISTS,
                        "Release your current seats for this show before choosing new ones",
                        Map.of("lockId", previous.getId()));
            }
        });
        try {
            locks.saveAndFlush(new SeatLock(lockId, userId, showId, sortedSeatIds.size(), now, expiresAt));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolation(e, ONE_ACTIVE_CONSTRAINT)) {   // concurrent request by the same user
                throw new ApiException(ErrorCode.ACTIVE_LOCK_EXISTS);
            }
            throw e;
        }

        List<Long> rowLocked = inventory.rowLockFree(showId, sortedSeatIds);
        int locked = rowLocked.size() == sortedSeatIds.size()
                ? inventory.lock(showId, sortedSeatIds, lockId, expiresAt, now)
                : 0;
        if (locked != sortedSeatIds.size()) {
            throw unavailable(lockId, seats, rowLocked);
        }
        long total = seats.stream().mapToLong(SeatLine::pricePaise).sum();
        return new SeatLockResponse(lockId, showId, SeatLockStatus.ACTIVE, BusinessTime.toLocal(expiresAt),
                inventory.heldBy(lockId), total);
    }

    @Transactional
    void release(Long userId, UUID lockId, Instant now) {
        SeatLock lock = locks.findByIdForUpdate(lockId)
                .filter(l -> l.isOwnedBy(userId))
                .orElseThrow(() -> new ApiException(ErrorCode.LOCK_NOT_FOUND));
        if (lock.getStatus() == SeatLockStatus.RELEASED) {
            return; // idempotent: a retried DELETE succeeds
        }
        if (lock.getStatus() != SeatLockStatus.ACTIVE) {
            throw new ApiException(ErrorCode.LOCK_NOT_ACTIVE, "Seat lock is " + lock.getStatus());
        }
        if (lifecycle.bookingOf(lock).map(Booking::getStatus).filter(s -> s.isOpen()).isPresent()) {
            throw new ApiException(ErrorCode.LOCK_IN_USE);
        }
        lifecycle.release(lock, now);
    }

    /**
     * Which seats were lost: if some rows were skipped (another transaction is on them), those; otherwise the
     * rows the conditional UPDATE did not take (held or sold). Still inside the doomed transaction, so our own
     * partial update is visible.
     */
    private ApiException unavailable(UUID lockId, List<SeatLine> requested, List<Long> rowLocked) {
        Set<Long> ok = new HashSet<>();
        if (rowLocked.size() != requested.size()) {
            ok.addAll(rowLocked);
        } else {
            inventory.heldBy(lockId).forEach(s -> ok.add(s.showSeatId()));
        }
        return seatsUnavailable(requested.stream().filter(s -> !ok.contains(s.showSeatId())).toList());
    }

    static ApiException seatsUnavailable(List<SeatLine> taken) {
        List<String> labels = taken.stream().map(SeatLine::label).toList();
        return new ApiException(ErrorCode.SEATS_UNAVAILABLE,
                taken.isEmpty() ? "Some selected seats are no longer available"
                        : "Seats " + String.join(", ", labels) + " are no longer available",
                Map.of("unavailableSeatIds", taken.stream().map(SeatLine::showSeatId).toList(),
                        "unavailableSeats", labels));
    }
}
