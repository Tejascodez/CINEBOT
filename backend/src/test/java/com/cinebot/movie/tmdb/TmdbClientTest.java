package com.cinebot.movie.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cinebot.movie.MovieMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Exercises JSON mapping + error translation against a fake TMDB; no network. */
class TmdbClientTest {

    private static final String BASE = "https://tmdb.test/3";

    private MockRestServiceServer server;

    private TmdbClient client(String token, String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TmdbClient(builder,
                new TmdbProperties(BASE, "https://img.test/t/p", token, apiKey, "IN", "en-US", null));
    }

    @Test
    void mapsMovieDetailsAndSendsBearerToken() {
        TmdbClient tmdb = client("secret-token", null);
        server.expect(requestTo(BASE + "/movie/157336?language=en-US"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess("""
                        {"id":157336,"title":"Interstellar","original_title":"Interstellar",
                         "overview":"Space.","original_language":"EN","release_date":"2014-11-05","runtime":169,
                         "poster_path":"/p.jpg","backdrop_path":"/b.jpg","vote_average":8.449,"popularity":151.2345,
                         "genres":[{"id":12,"name":"Adventure"},{"id":878,"name":"Science Fiction"}],
                         "some_new_field":"ignored"}
                        """, MediaType.APPLICATION_JSON));

        MovieMetadata movie = tmdb.fetchMovieDetails(157336);

        assertThat(movie.title()).isEqualTo("Interstellar");
        assertThat(movie.originalLanguage()).isEqualTo("en");
        assertThat(movie.releaseDate()).isEqualTo(LocalDate.of(2014, 11, 5));
        assertThat(movie.runtimeMinutes()).isEqualTo(169);
        assertThat(movie.voteAverage()).isEqualByComparingTo(new BigDecimal("8.4"));
        assertThat(movie.popularity()).isEqualByComparingTo(new BigDecimal("151.235"));
        assertThat(movie.genres()).extracting(MovieMetadata.GenreRef::name)
                .containsExactly("Adventure", "Science Fiction");
        server.verify();
    }

    @Test
    void toleratesEmptyReleaseDateAndZeroRuntime() {
        TmdbClient tmdb = client("t", null);
        server.expect(requestTo(BASE + "/movie/1?language=en-US"))
                .andRespond(withSuccess("""
                        {"id":1,"title":"Unreleased","release_date":"","runtime":0,"genres":[]}
                        """, MediaType.APPLICATION_JSON));

        MovieMetadata movie = tmdb.fetchMovieDetails(1);

        assertThat(movie.releaseDate()).isNull();
        assertThat(movie.runtimeMinutes()).isNull();
    }

    @Test
    void fallsBackToApiKeyQueryParamWhenNoToken() {
        TmdbClient tmdb = client(null, "v3-key");
        server.expect(requestTo(Matchers.startsWith(BASE + "/movie/now_playing")))
                .andExpect(queryParam("api_key", "v3-key"))
                .andExpect(queryParam("region", "IN"))
                .andExpect(queryParam("page", "2"))
                .andRespond(withSuccess("""
                        {"page":2,"total_pages":5,"results":[{"id":11},{"id":22}]}
                        """, MediaType.APPLICATION_JSON));

        TmdbClient.NowPlayingPage page = tmdb.fetchNowPlaying(2);

        assertThat(page.totalPages()).isEqualTo(5);
        assertThat(page.tmdbIds()).containsExactly(11L, 22L);
    }

    @Test
    void serverErrorBecomesTmdbExceptionWithoutLeakingApiKey() {
        TmdbClient tmdb = client(null, "v3-key");
        server.expect(requestTo(Matchers.startsWith(BASE + "/genre/movie/list")))
                .andRespond(withServerError());

        assertThatThrownBy(tmdb::fetchMovieGenres)
                .isInstanceOf(TmdbException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("v3-key");
    }

    @Test
    void unauthorizedBecomesTmdbException() {
        TmdbClient tmdb = client("bad", null);
        server.expect(requestTo(BASE + "/movie/5?language=en-US")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> tmdb.fetchMovieDetails(5))
                .isInstanceOf(TmdbException.class).hasMessageContaining("401");
    }

    @Test
    void reportsConfiguredOnlyWithCredentials() {
        assertThat(client(null, null).isConfigured()).isFalse();
        assertThat(client("", " ").isConfigured()).isFalse();
        assertThat(client("t", null).isConfigured()).isTrue();
    }
}
