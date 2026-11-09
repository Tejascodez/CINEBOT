package com.cinebot.screen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * References its theatre by id only (no @ManyToOne Theatre): keeps the dependency one-way
 * (theatre module -> screen module), so the theatre module can list screens without a package cycle.
 */
@Entity
@Table(name = "screens")
public class Screen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "theatre_id", nullable = false, updatable = false)
    private Long theatreId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "screen_type", nullable = false)
    private ScreenType screenType;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Screen() {
        // JPA
    }

    public Screen(Long theatreId, String name, ScreenType screenType) {
        this.theatreId = theatreId;
        this.name = name;
        this.screenType = screenType;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    void setTotalSeats(int totalSeats) {
        this.totalSeats = totalSeats;
    }

    public Long getId() {
        return id;
    }

    public Long getTheatreId() {
        return theatreId;
    }

    public String getName() {
        return name;
    }

    public ScreenType getScreenType() {
        return screenType;
    }

    public int getTotalSeats() {
        return totalSeats;
    }
}
