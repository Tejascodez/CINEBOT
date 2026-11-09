package com.cinebot.agent.tools;

import com.cinebot.genre.GenreService;
import com.cinebot.movie.MovieService;
import com.cinebot.movie.MovieSort;
import com.cinebot.movie.dto.MovieDetailResponse;
import com.cinebot.movie.dto.MovieListQuery;
import com.cinebot.movie.dto.MovieSearchQuery;
import com.cinebot.movie.dto.MovieSummaryResponse;
import com.cinebot.genre.dto.GenreResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Movie discovery. Backs GET /api/v1/movies, /movies/search and /movies/{id} (same service methods). */
@Component
public class MovieTools {

    record MovieItem(Long movieId, String title, String language, List<String> genres, Integer runtimeMinutes,
                     BigDecimal rating, String releaseDate) {
    }

    private final MovieService movies;
    private final GenreService genres;

    public MovieTools(MovieService movies, GenreService genres) {
        this.movies = movies;
        this.genres = genres;
    }

    @Tool(name = "searchMovies", description = """
            Find movies. Use it whenever the user names a movie (any spelling; matching is typo tolerant), \
            describes one (genre, language), or asks what is playing, including vague requests such as \
            'find me a movie for tonight'. With a title, other filters are ignored. Without a title, returns \
            popular movies filtered by genre / language / city. Returns movieIds for the other tools.""")
    public ToolReply searchMovies(
            @ToolParam(required = false, description = "Movie title or part of it, e.g. 'interstellar'") String title,
            @ToolParam(required = false, description = "Genre name, e.g. 'Comedy', 'Action'") String genre,
            @ToolParam(required = false, description = "2-letter ISO 639-1 language code, e.g. en, hi, ta") String language,
            @ToolParam(required = false, description = "Only movies with upcoming shows in this city") String city,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        String query = ToolArgs.optionalText(title, "title", 100);
        String cityName = ToolArgs.optionalText(city, "city", 100);
        List<MovieSummaryResponse> found;
        if (query != null) {
            if (query.length() < 2) {
                throw new InvalidToolArgumentException("title must be at least 2 characters");
            }
            found = movies.search(new MovieSearchQuery(query, 0, 8)).content();
        } else {
            Long genreId = genreId(ToolArgs.optionalText(genre, "genre", 40));
            found = movies.list(new MovieListQuery(genreId, cityName, ToolArgs.optionalLanguage(language),
                    MovieSort.POPULARITY, 0, 8)).content();
        }
        if (cityName != null) {
            turn.state().selectCity(cityName);
        }
        if (found.isEmpty()) {
            return ToolReply.error("NO_MOVIES_FOUND", "No movies matched. Ask the user to rephrase or broaden the search.");
        }
        if (found.size() == 1 || (query != null && found.getFirst().title().equalsIgnoreCase(query))) {
            turn.state().selectMovie(found.getFirst().id(), found.getFirst().title());
        }
        turn.show(new AgentCard(AgentCard.Type.MOVIES, found));
        List<MovieItem> items = found.stream().map(m -> new MovieItem(m.id(), m.title(), m.language(), m.genres(),
                m.runtimeMinutes(), m.rating(), Display.date(m.releaseDate()))).toList();
        return ToolReply.ok(items.size() + " movie(s) found.", Map.of("movies", items));
    }

    @Tool(name = "getMovieDetails", description = """
            Get the full details of one movie (plot overview, runtime, genres, rating, release date). Use it when \
            the user asks about a specific movie, or to confirm which movie they mean.""")
    public ToolReply getMovieDetails(
            @ToolParam(description = "movieId from searchMovies") Long movieId,
            ToolContext toolContext) {
        AgentTurn turn = AgentTurn.from(toolContext);
        MovieDetailResponse m = movies.get(ToolArgs.requireId(movieId, "movieId"));
        turn.state().selectMovie(m.id(), m.title());
        turn.show(new AgentCard(AgentCard.Type.MOVIE_DETAILS, m));
        return ToolReply.ok(m.title() + " details.", Map.of(
                "movieId", m.id(), "title", m.title(), "overview", m.overview() == null ? "" : m.overview(),
                "language", m.language(), "runtimeMinutes", m.runtimeMinutes() == null ? "unknown" : m.runtimeMinutes(),
                "genres", m.genres().stream().map(GenreResponse::name).toList(),
                "rating", m.rating() == null ? "unrated" : m.rating(),
                "releaseDate", m.releaseDate() == null ? "unknown" : m.releaseDate().toString()));
    }

    private Long genreId(String name) {
        if (name == null) {
            return null;
        }
        return genres.listAll().stream().filter(g -> g.name().equalsIgnoreCase(name)).map(GenreResponse::id)
                .findFirst()
                .orElseThrow(() -> new InvalidToolArgumentException("unknown genre '" + name + "'; known genres: "
                        + String.join(", ", genres.listAll().stream().map(GenreResponse::name).toList())));
    }
}
