package com.cinebot.show;

import com.cinebot.movie.Movie;
import com.cinebot.screen.Screen;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false, updatable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false, updatable = false)
    private Screen screen;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** start + runtime + turnaround: when the screen is free again. */
    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false)
    private String format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowStatus status = ShowStatus.SCHEDULED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Show() {
        // JPA
    }

    Show(Movie movie, Screen screen, Instant startTime, Instant endTime, String language, String format) {
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.endTime = endTime;
        this.language = language;
        this.format = format;
    }

    /** @return false if it was already cancelled (idempotent cancel). */
    boolean cancel() {
        if (status == ShowStatus.CANCELLED) {
            return false;
        }
        status = ShowStatus.CANCELLED;
        return true;
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

    public Long getId() {
        return id;
    }

    public Movie getMovie() {
        return movie;
    }

    public Screen getScreen() {
        return screen;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public ShowStatus getStatus() {
        return status;
    }
}
