package com.cinebot.seat;

import com.cinebot.screen.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One physical seat for one show: the unit of inventory. Holds the show by id (not @ManyToOne Show) so the
 * dependency stays one-way: the show module uses the seat module, never the reverse.
 */
@Entity
@Table(name = "show_seats")
public class ShowSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false, updatable = false)
    private Seat seat;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowSeatStatus status;

    /** Set while LOCKED: the seat lock that holds this seat. */
    @Column(name = "lock_id")
    private java.util.UUID lockId;

    @Column(name = "locked_until")
    private java.time.Instant lockedUntil;

    /** Set while BOOKED. */
    @Column(name = "booking_id")
    private Long bookingId;

    /** Optimistic-locking guard for entity updates; Step 4's conditional UPDATEs bump it explicitly. */
    @Version
    private long version;

    protected ShowSeat() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public Seat getSeat() {
        return seat;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public ShowSeatStatus getStatus() {
        return status;
    }

    /**
     * Status as clients should see it. An expired lock is reported AVAILABLE immediately ("lazy expiry"):
     * the lock-acquire query treats it as free too, so the sweeper is cleanup, not a correctness dependency.
     */
    public ShowSeatStatus effectiveStatus(java.time.Instant now) {
        return status == ShowSeatStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now)
                ? ShowSeatStatus.AVAILABLE : status;
    }

    public java.util.UUID getLockId() {
        return lockId;
    }

    public Long getBookingId() {
        return bookingId;
    }
}
