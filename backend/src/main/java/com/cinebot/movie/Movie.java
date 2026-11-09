package com.cinebot.movie;

import com.cinebot.genre.Genre;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_id", nullable = false, unique = true)
    private Long tmdbId;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(columnDefinition = "text")
    private String overview;

    @Column(name = "original_language")
    private String originalLanguage;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "runtime_minutes")
    private Integer runtimeMinutes;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "backdrop_path")
    private String backdropPath;

    @Column(name = "vote_average", precision = 3, scale = 1)
    private BigDecimal voteAverage;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal popularity = BigDecimal.ZERO;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * LAZY + hibernate.default_batch_fetch_size: listing a page of movies loads all their genres in one extra query.
     * A JOIN FETCH here would break DB-side pagination (Hibernate would paginate in memory).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new LinkedHashSet<>();

    protected Movie() {
        // JPA
    }

    public Movie(Long tmdbId) {
        this.tmdbId = tmdbId;
    }

    /** Overwrites metadata with TMDB's current values. TMDB is the source of truth for metadata. */
    public void applyMetadata(MovieMetadata metadata, Collection<Genre> newGenres, Instant syncedAt) {
        this.title = metadata.title();
        this.originalTitle = metadata.originalTitle();
        this.overview = metadata.overview();
        this.originalLanguage = metadata.originalLanguage();
        this.releaseDate = metadata.releaseDate();
        this.runtimeMinutes = metadata.runtimeMinutes();
        this.posterPath = metadata.posterPath();
        this.backdropPath = metadata.backdropPath();
        this.voteAverage = metadata.voteAverage();
        this.popularity = metadata.popularity() == null ? BigDecimal.ZERO : metadata.popularity();
        this.genres.clear();
        this.genres.addAll(newGenres);
        this.syncedAt = syncedAt;
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

    public Long getTmdbId() {
        return tmdbId;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public String getOverview() {
        return overview;
    }

    public String getOriginalLanguage() {
        return originalLanguage;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public Integer getRuntimeMinutes() {
        return runtimeMinutes;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public String getBackdropPath() {
        return backdropPath;
    }

    public BigDecimal getVoteAverage() {
        return voteAverage;
    }

    public BigDecimal getPopularity() {
        return popularity;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public Set<Genre> getGenres() {
        return genres;
    }
}
