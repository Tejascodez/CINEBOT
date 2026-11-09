package com.cinebot.movie;

import com.cinebot.common.web.PageResponse;
import com.cinebot.movie.dto.MovieDetailResponse;
import com.cinebot.movie.dto.MovieListQuery;
import com.cinebot.movie.dto.MovieSearchQuery;
import com.cinebot.movie.dto.MovieSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    /** GET /api/v1/movies?genreId=35&language=hi&sort=RATING&page=0&size=20 */
    @GetMapping
    public PageResponse<MovieSummaryResponse> list(@Valid @ModelAttribute MovieListQuery query) {
        return movieService.list(query);
    }

    /** GET /api/v1/movies/search?q=intersteller */
    @GetMapping("/search")
    public PageResponse<MovieSummaryResponse> search(@Valid @ModelAttribute MovieSearchQuery query) {
        return movieService.search(query);
    }

    @GetMapping("/{id}")
    public MovieDetailResponse get(@PathVariable Long id) {
        return movieService.get(id);
    }
}
