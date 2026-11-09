package com.cinebot.screen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A physical seat. Per-show availability lives in show_seats, never here. */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false, updatable = false)
    private Long screenId;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    /** Display order of the row (layout input order), so "AA" can follow "Z". */
    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    protected Seat() {
        // JPA
    }

    Seat(Long screenId, String rowLabel, int rowIndex, int seatNumber, SeatType seatType) {
        this.screenId = screenId;
        this.rowLabel = rowLabel;
        this.rowIndex = rowIndex;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
    }

    public Long getId() {
        return id;
    }

    public Long getScreenId() {
        return screenId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    /** Human label used by UI and chatbot ("A1", "B12"). */
    public String getLabel() {
        return rowLabel + seatNumber;
    }
}
