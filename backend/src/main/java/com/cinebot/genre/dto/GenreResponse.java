package com.cinebot.genre.dto;

import com.cinebot.genre.Genre;

public record GenreResponse(Long id, String name) {

    public static GenreResponse from(Genre genre) {
        return new GenreResponse(genre.getId(), genre.getName());
    }
}
