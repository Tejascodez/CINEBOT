package com.cinebot.movie;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.web.PageResponse;
import com.cinebot.movie.dto.MovieDetailResponse;
import com.cinebot.movie.dto.MovieListQuery;
import com.cinebot.movie.dto.MovieSearchQuery;
import com.cinebot.movie.dto.MovieSummaryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the catalog. Always served from PostgreSQL: TMDB is only contacted by the sync job, never per request.
 * readOnly transactions keep the session open while lazy genres are mapped to DTOs (open-in-view is off).
 */
@Service
public class MovieService {

    private final MovieRepository movies;
    private final MovieMapper mapper;
    private final NowShowingQuery nowShowing;

    public MovieService(MovieRepository movies, MovieMapper mapper, NowShowingQuery nowShowing) {
        this.movies = movies;
        this.mapper = mapper;
        this.nowShowing = nowShowing;
    }

    @Transactional(readOnly = true)
    public PageResponse<MovieSummaryResponse> list(MovieListQuery query) {
        List<Specification<Movie>> filters = new ArrayList<>();
        if (query.genreId() != null) {
            filters.add(MovieSpecifications.hasGenre(query.genreId()));
        }
        if (query.language() != null) {
            filters.add(MovieSpecifications.hasLanguage(query.language()));
        }
        var pageable = PageRequest.of(query.page(), query.size(), query.sort().sort());
        if (query.city() != null && !query.city().isBlank()) {
            // A city has at most a few hundred movies with upcoming shows, so an id set is cheap.
            Set<Long> ids = nowShowing.movieIdsShowingInCity(query.city().trim());
            if (ids.isEmpty()) {
                return PageResponse.from(Page.<Movie>empty(pageable), mapper::toSummary);
            }
            filters.add(MovieSpecifications.idIn(ids));
        }
        return PageResponse.from(movies.findAll(Specification.allOf(filters), pageable), mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public MovieDetailResponse get(Long id) {
        return movies.findById(id).map(mapper::toDetail)
                .orElseThrow(() -> new ApiException(ErrorCode.MOVIE_NOT_FOUND, "Movie " + id + " not found"));
    }

    /** For modules that reference movies (shows). */
    @Transactional(readOnly = true)
    public Movie requireMovie(Long id) {
        return movies.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.MOVIE_NOT_FOUND, "Movie " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovieSummaryResponse> search(MovieSearchQuery query) {
        String normalized = query.q().trim().toLowerCase(Locale.ROOT);
        String likePattern = "%" + escapeLike(normalized) + "%";
        var pageable = PageRequest.of(query.page(), query.size()); // ordering is defined by the query itself
        return PageResponse.from(movies.searchByTitle(normalized, likePattern, pageable), mapper::toSummary);
    }

    /** User input must not act as LIKE wildcards: "%" alone would otherwise match every movie. */
    static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
