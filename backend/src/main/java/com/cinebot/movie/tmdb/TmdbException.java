package com.cinebot.movie.tmdb;

/** Any failure talking to TMDB: network, timeout, non-2xx, or unparseable body. */
public class TmdbException extends RuntimeException {

    public TmdbException(String message, Throwable cause) {
        super(message, cause);
    }

    public TmdbException(String message) {
        super(message);
    }
}
