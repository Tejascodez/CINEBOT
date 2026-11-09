package com.cinebot.booking.lock;

import com.cinebot.booking.Booking;
import com.cinebot.booking.BookingRepository;
import com.cinebot.booking.BookingStatus;
import com.cinebot.booking.BookingTransitions;
import com.cinebot.seat.SeatLine;
import com.cinebot.seat.ShowSeatInventory;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Closing a lock = free its seats in the DB + mark the lock + (after commit) clear its Redis keys.
 * Shared by user release, booking cancel, the sweeper, and "replace my own expired lock".
 *
 * <p><b>Lock ordering rule</b> (prevents deadlocks): every path locks the seat_locks row BEFORE the bookings row.
 */
@Component
public class SeatLockLifecycle {

    private final ShowSeatInventory inventory;
    private final BookingRepository bookings;
    private final RedisSeatLockGate gate;
    private final BookingTransitions transitions;

    public SeatLockLifecycle(ShowSeatInventory inventory, BookingRepository bookings, RedisSeatLockGate gate,
                             BookingTransitions transitions) {
        this.inventory = inventory;
        this.bookings = bookings;
        this.gate = gate;
        this.transitions = transitions;
    }

    /** Expires a lock and, if it backs an open booking, the booking too. Caller holds the lock row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(SeatLock lock, Instant now) {
        // Captures are recorded and confirmed in ONE transaction (payment module), so an open booking never
        // has a captured payment here. A capture arriving after this expiry is refunded.
        bookings.findBySeatLockIdForUpdate(lock.getId())
                .filter(b -> b.getStatus().isOpen())
                .ifPresent(b -> transitions.move(b, BookingStatus.EXPIRED, now));
        close(lock, SeatLockStatus.EXPIRED, now);
    }

    /** Releases a lock whose booking (if any) the caller has already moved to a closed state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(SeatLock lock, Instant now) {
        close(lock, SeatLockStatus.RELEASED, now);
    }

    /**
     * Booking confirmed: the seats are now BOOKED (no longer held by the lock), so nothing is freed;
     * the lock is closed as CONSUMED and its Redis keys removed after commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(SeatLock lock, List<Long> bookedSeatIds, Instant now) {
        lock.close(SeatLockStatus.CONSUMED, now);
        afterCommit(() -> gate.release(lock.getShowId(), bookedSeatIds, lock.getId()));
    }

    /** Booking-side helper: find the booking row for a lock the caller already holds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.Optional<Booking> bookingOf(SeatLock lock) {
        return bookings.findBySeatLockIdForUpdate(lock.getId());
    }

    private void close(SeatLock lock, SeatLockStatus outcome, Instant now) {
        List<Long> held = inventory.heldBy(lock.getId()).stream().map(SeatLine::showSeatId).toList();
        inventory.release(lock.getId());
        lock.close(outcome, now);
        // Only after the DB commit: if the transaction rolls back, the seats are still ours, so are the keys.
        afterCommit(() -> gate.release(lock.getShowId(), held, lock.getId()));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
