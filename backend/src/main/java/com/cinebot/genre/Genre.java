package com.cinebot.genre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "genres")
public class Genre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_genre_id", unique = true)
    private Integer tmdbGenreId;

    @Column(nullable = false, unique = true)
    private String name;

    protected Genre() {
        // JPA
    }

    public Genre(Integer tmdbGenreId, String name) {
        this.tmdbGenreId = tmdbGenreId;
        this.name = name;
    }

    void rename(String newName) {
        this.name = newName;
    }

    public Long getId() {
        return id;
    }

    public Integer getTmdbGenreId() {
        return tmdbGenreId;
    }

    public String getName() {
        return name;
    }
}
