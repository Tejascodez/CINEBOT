package com.cinebot.movie;

import org.springframework.data.domain.Sort;

/**
 * Whitelisted sort options. Accepting arbitrary "sort=anyField" from clients would let them sort on
 * unindexed columns or trigger errors for unknown properties.
 */
public enum MovieSort {
    POPULARITY(Sort.by(Sort.Order.desc("popularity"), Sort.Order.asc("id"))),
    RELEASE_DATE(Sort.by(Sort.Order.desc("releaseDate"), Sort.Order.asc("id"))),
    RATING(Sort.by(Sort.Order.desc("voteAverage"), Sort.Order.asc("id"))),
    TITLE(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));

    private final Sort sort;

    MovieSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
