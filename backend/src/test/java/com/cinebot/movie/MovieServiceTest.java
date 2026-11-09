package com.cinebot.movie;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MovieServiceTest {

    @Test
    void escapesLikeWildcardsSoUserInputIsLiteral() {
        assertThat(MovieService.escapeLike("100%")).isEqualTo("100\\%");
        assertThat(MovieService.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(MovieService.escapeLike("c:\\x")).isEqualTo("c:\\\\x");
        assertThat(MovieService.escapeLike("plain")).isEqualTo("plain");
    }
}
