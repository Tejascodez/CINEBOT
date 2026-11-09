package com.cinebot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookingReferenceGeneratorTest {

    private final BookingReferenceGenerator generator = new BookingReferenceGenerator();

    @Test
    void referencesAreReadableAndFreeOfLookAlikes() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.next()).matches("^CB[2-9A-HJKMNP-TV-Z]{8}$").doesNotContain("O", "I", "L", "U", "0", "1");
        }
    }

    @Test
    void referencesDoNotRepeatInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).hasSize(50_000);
    }
}
