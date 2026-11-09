package com.cinebot.auth;

import com.cinebot.user.Role;
import com.cinebot.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived access tokens (HS256).
 *
 * <p>Security notes:
 * <ul>
 *   <li>verifyWith(SecretKey) pins verification to HMAC; unsigned ("alg":"none") tokens are rejected by jjwt.</li>
 *   <li>Issuer is required, so tokens minted for another system with the same secret are refused.</li>
 *   <li>Access tokens are not revocable; that is why they live only 15 minutes. Revocation happens at refresh time.</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits, required for HS256
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(JwtProperties properties, Clock clock) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("cinebot.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(secret);
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String generateAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** @return the authenticated user, or empty if the token is malformed, tampered, expired, or foreign. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // IllegalArgumentException also covers a bad subject number or unknown role.
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
