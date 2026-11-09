package com.cinebot.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.support.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Public catalog reads: filters, sorting, pagination, typo-tolerant search, validation, query counts. */
class MovieCatalogIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private EntityManagerFactory emf;

    private long comedy;
    private long drama;
    private long interstellar;

    @BeforeEach
    void seed() {
        comedy = genre(35, "Comedy");
        drama = genre(18, "Drama");
        long scifi = genre(878, "Science Fiction");
        interstellar = movie(157336, "Interstellar", "en", 150, "/interstellar.jpg", scifi, drama);
        movie(155, "The Dark Knight", "en", 120, null, drama);
        movie(20453, "3 Idiots", "hi", 90, null, comedy, drama);
        movie(360814, "Dangal", "hi", 80, null, drama);
        movie(999, "100% Love", "te", 10, null, comedy);
    }

    @Test
    void listsByPopularityWithGenresAndImageUrls() throws Exception {
        mvc.perform(get("/api/v1/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].title")
                        .value(contains("Interstellar", "The Dark Knight", "3 Idiots", "Dangal", "100% Love")))
                .andExpect(jsonPath("$.content[0].genres").value(contains("Drama", "Science Fiction")))
                .andExpect(jsonPath("$.content[0].posterUrl").value("https://image.tmdb.org/t/p/w500/interstellar.jpg"))
                .andExpect(jsonPath("$.content[1].posterUrl").doesNotExist());
    }

    @Test
    void genreFilterDoesNotDuplicateRows() throws Exception {
        mvc.perform(get("/api/v1/movies").param("genreId", String.valueOf(comedy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title").value(contains("3 Idiots", "100% Love")));
    }

    @Test
    void combinesGenreAndLanguageFilters() throws Exception {
        mvc.perform(get("/api/v1/movies").param("genreId", String.valueOf(drama)).param("language", "hi"))
                .andExpect(jsonPath("$.content[*].title").value(contains("3 Idiots", "Dangal")));
    }

    @Test
    void paginatesAndSortsByWhitelistedField() throws Exception {
        mvc.perform(get("/api/v1/movies").param("sort", "TITLE").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[*].title").value(contains("Dangal", "Interstellar")));
    }

    @Test
    void rejectsInvalidListParameters() throws Exception {
        mvc.perform(get("/api/v1/movies").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("size")));
        mvc.perform(get("/api/v1/movies").param("language", "English"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("language")));
        mvc.perform(get("/api/v1/movies").param("sort", "password_hash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("sort")));
    }

    @Test
    void searchToleratesTypos() throws Exception {
        mvc.perform(get("/api/v1/movies/search").param("q", "intersteller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Interstellar"));
    }

    @Test
    void searchMatchesPartialTitlesCaseInsensitively() throws Exception {
        mvc.perform(get("/api/v1/movies/search").param("q", "DARK KN"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("The Dark Knight"));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        // Unescaped, "%" would match everything; escaped, it only matches the literal "%" in "100% Love".
        mvc.perform(get("/api/v1/movies/search").param("q", "0%"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("100% Love"));
        mvc.perform(get("/api/v1/movies/search").param("q", "__"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchRequiresAtLeastTwoCharacters() throws Exception {
        mvc.perform(get("/api/v1/movies/search").param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsMovieDetail() throws Exception {
        mvc.perform(get("/api/v1/movies/{id}", interstellar))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdbId").value(157336))
                .andExpect(jsonPath("$.runtimeMinutes").value(120))
                .andExpect(jsonPath("$.genres[*].name").value(contains("Drama", "Science Fiction")));
    }

    @Test
    void unknownMovieIs404AndBadIdIs400() throws Exception {
        mvc.perform(get("/api/v1/movies/{id}", 987654))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
        mvc.perform(get("/api/v1/movies/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsGenresAlphabetically() throws Exception {
        mvc.perform(get("/api/v1/genres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Comedy", "Drama", "Science Fiction")));
    }

    @Test
    void listingUsesConstantNumberOfQueries() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        mvc.perform(get("/api/v1/movies").param("size", "5")).andExpect(status().isOk());

        // page query + count query + ONE batched genre query. Without batch fetching this would be 2 + 5 (N+1).
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    // --- fixtures ----------------------------------------------------------------------------------------------

    private long genre(int tmdbId, String name) {
        return jdbc.queryForObject("INSERT INTO genres (tmdb_genre_id, name) VALUES (?, ?) RETURNING id",
                Long.class, tmdbId, name);
    }

    private long movie(long tmdbId, String title, String lang, int popularity, String poster, long... genreIds) {
        long id = jdbc.queryForObject("""
                INSERT INTO movies (tmdb_id, title, original_language, popularity, poster_path, runtime_minutes)
                VALUES (?, ?, ?, ?, ?, 120) RETURNING id
                """, Long.class, tmdbId, title, lang, popularity, poster);
        for (long genreId : genreIds) {
            jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", id, genreId);
        }
        return id;
    }
}
