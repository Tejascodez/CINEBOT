package com.cinebot.auth;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens with rotation and reuse detection.
 *
 * <p>Each login starts a token <em>family</em>. Every refresh revokes the presented token and issues a new one
 * in the same family. If an already-revoked token is presented again, someone (attacker or a stolen copy)
 * is replaying it, so the entire family is revoked and the user must log in again.
 *
 * <p>Tokens are 256-bit random values, so a fast SHA-256 hash is sufficient for storage (unlike passwords,
 * they cannot be brute-forced), and it gives an indexable, deterministic lookup key.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new family (login / register). */
    @Transactional
    public String issueNewFamily(User user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Rotates a refresh token.
     *
     * <p>{@code noRollbackFor}: on reuse we revoke the family and then throw. Without this, the exception
     * would roll back the very revocation we need to persist.
     *
     * @return id of the token owner plus the new raw token
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {} (family {}); revoked {} active tokens",
                    current.getUser().getId(), current.getFamilyId(), revoked);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSED, "Session revoked. Please log in again.");
        }
        if (current.isExpired(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token expired");
        }

        current.revoke(now);
        User user = current.getUser();
        String next = issue(user, current.getFamilyId());
        return new Rotation(user.getId(), next);
    }

    /** Logout: revokes the whole family of the given token. Unknown tokens are ignored (idempotent). */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private String issue(User user, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new RefreshToken(user, hash(raw), familyId, now, now.plus(properties.refreshTokenTtl())));
        return raw;
    }

    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Rotation(Long userId, String refreshToken) {
    }
}
