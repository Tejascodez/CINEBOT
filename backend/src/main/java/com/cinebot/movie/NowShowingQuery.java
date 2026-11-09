package com.cinebot.movie;

import java.util.Set;

/**
 * Port owned by the movie module, implemented by the show module (dependency inversion).
 * The movie catalog can filter "now showing in Chennai" without depending on show/theatre code,
 * which would create a package cycle because shows already depend on movies.
 */
public interface NowShowingQuery {

    Set<Long> movieIdsShowingInCity(String city);
}
