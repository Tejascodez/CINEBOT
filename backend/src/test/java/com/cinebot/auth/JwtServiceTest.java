package com.cinebot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cinebot.user.Role;
import com.cinebot.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnop";
    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final JwtService jwt = new JwtService(props(SECRET, "cinebot"), clock);

    @Test
    void generatedTokenParsesBackToSameUser() {
        String token = jwt.generateAccessToken(user(42L, "a@b.com", Role.ADMIN));

        Optional<AuthenticatedUser> parsed = jwt.parse(token);

        assertThat(parsed).contains(new AuthenticatedUser(42L, "a@b.com", Role.ADMIN));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwt.generateAccessToken(user(1L, "a@b.com", Role.USER));

        clock.advance(Duration.ofMinutes(16)); // TTL is 15 min

        assertThat(jwt.parse(token)).isEmpty();
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = jwt.generateAccessToken(user(1L, "a@b.com", Role.USER));
        String[] parts = token.split("\\.");
        // Swap payload with one claiming ADMIN, keep the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"cinebot\",\"sub\":\"1\",\"email\":\"a@b.com\",\"role\":\"ADMIN\",\"exp\":9999999999}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwt.parse(forged)).isEmpty();
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        JwtService other = new JwtService(props("another-secret-0123456789-abcdefghijklmnop", "cinebot"), clock);
        String token = other.generateAccessToken(user(1L, "a@b.com", Role.USER));

        assertThat(jwt.parse(token)).isEmpty();
    }

    @Test
    void tokenFromDifferentIssuerIsRejected() {
        JwtService other = new JwtService(props(SECRET, "someone-else"), clock);
        String token = other.generateAccessToken(user(1L, "a@b.com", Role.USER));

        assertThat(jwt.parse(token)).isEmpty();
    }

    @Test
    void unsignedNoneAlgorithmTokenIsRejected() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"cinebot\",\"sub\":\"1\",\"email\":\"a@b.com\",\"role\":\"ADMIN\"}".getBytes());

        assertThat(jwt.parse(header + "." + payload + ".")).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(jwt.parse("not-a-jwt")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
    }

    @Test
    void shortSecretFailsFastAtStartup() {
        assertThatThrownBy(() -> new JwtService(props("too-short", "cinebot"), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JwtProperties props(String secret, String issuer) {
        return new JwtProperties(secret, issuer, Duration.ofMinutes(15), Duration.ofDays(30));
    }

    private static User user(Long id, String email, Role role) {
        User user = new User(email, "hash", "Test User", null, role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
