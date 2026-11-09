package com.cinebot.booking;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.seat.SeatLine;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_reference", nullable = false, unique = true, updatable = false)
    private String bookingReference;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @Column(name = "seat_lock_id", nullable = false, unique = true, updatable = false)
    private UUID seatLockId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "total_amount_paise", nullable = false, updatable = false)
    private long totalAmountPaise;

    @Column(nullable = false, updatable = false)
    private String currency = "INR";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Second line of defence after row locks: a stale in-memory copy can never overwrite a newer state. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<BookingSeat> seats = new ArrayList<>();

    protected Booking() {
        // JPA
    }

    static Booking pending(String reference, Long userId, Long showId, UUID seatLockId, List<SeatLine> heldSeats,
                           Instant expiresAt) {
        Booking booking = new Booking();
        booking.bookingReference = reference;
        booking.userId = userId;
        booking.showId = showId;
        booking.seatLockId = seatLockId;
        booking.status = BookingStatus.PENDING;
        booking.expiresAt = expiresAt;
        for (SeatLine seat : heldSeats) {
            booking.seats.add(new BookingSeat(booking, seat));
            booking.totalAmountPaise += seat.pricePaise();
        }
        return booking;
    }

    /**
     * Applies the state machine; invalid transitions are rejected with 409 INVALID_STATE_TRANSITION.
     * Package-private on purpose: services must go through BookingTransitions, which also publishes the event.
     */
    void transitionTo(BookingStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Booking " + bookingReference + " cannot move from " + status + " to " + next);
        }
        status = next;
        switch (next) {
            case CONFIRMED -> confirmedAt = now;
            case CANCELLED, EXPIRED, PAYMENT_FAILED, REFUNDED -> closedAt = now;
            default -> { }
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isOwnedBy(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public Long getId() {
        return id;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getShowId() {
        return showId;
    }

    public UUID getSeatLockId() {
        return seatLockId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public long getTotalAmountPaise() {
        return totalAmountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<BookingSeat> getSeats() {
        return seats;
    }
}
