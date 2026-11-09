package com.cinebot.booking;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.SeatLine;
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

/** Snapshot of one purchased seat: later price or layout changes can never alter a booking's history. */
@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @Column(name = "show_seat_id", nullable = false, updatable = false)
    private Long showSeatId;

    @Column(name = "seat_label", nullable = false, updatable = false)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, updatable = false)
    private SeatType seatType;

    @Column(name = "price_paise", nullable = false, updatable = false)
    private long pricePaise;

    /** Flipped to true at confirmation (Step 5); a partial unique index allows one confirmed row per show seat. */
    @Column(nullable = false)
    private boolean confirmed;

    protected BookingSeat() {
        // JPA
    }

    BookingSeat(Booking booking, SeatLine seat) {
        this.booking = booking;
        this.showSeatId = seat.showSeatId();
        this.seatLabel = seat.label();
        this.seatType = seat.type();
        this.pricePaise = seat.pricePaise();
    }

    void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public Long getShowSeatId() {
        return showSeatId;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
