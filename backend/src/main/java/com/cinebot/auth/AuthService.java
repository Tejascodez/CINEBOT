package com.cinebot.auth;

import com.cinebot.auth.dto.AuthResponse;
import com.cinebot.auth.dto.LoginRequest;
import com.cinebot.auth.dto.RefreshRequest;
import com.cinebot.auth.dto.RegisterRequest;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.user.Role;
import com.cinebot.user.User;
import com.cinebot.user.UserService;
import com.cinebot.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserService userService;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    /** Compared against when the email is unknown, so "no such user" and "wrong password" take the same time. */
    private final String dummyHash;

    public AuthService(UserService userService, RefreshTokenService refreshTokens, JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.refreshTokens = refreshTokens;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("timing-equalisation-dummy-password");
    }

    /** User row and first refresh token are created atomically. Self-registration always yields role USER. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = userService.createUser(request.email(), passwordEncoder.encode(request.password()),
                request.fullName(), request.phone(), Role.USER);
        return tokensFor(user, refreshTokens.issueNewFamily(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = userService.findByEmail(request.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokensFor(user, refreshTokens.issueNewFamily(user));
    }

    /**
     * Not @Transactional on purpose: rotate() commits its own transaction, including the family revocation
     * on reuse. Wrapping it in an outer transaction would roll that revocation back when the exception propagates.
     */
    public AuthResponse refresh(RefreshRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(request.refreshToken());
        User user = userService.getById(rotation.userId());
        return tokensFor(user, rotation.refreshToken());
    }

    public void logout(RefreshRequest request) {
        refreshTokens.revokeFamilyOf(request.refreshToken());
    }

    private AuthResponse tokensFor(User user, String refreshToken) {
        return AuthResponse.bearer(jwtService.generateAccessToken(user), refreshToken,
                jwtService.accessTokenTtlSeconds(), UserResponse.from(user));
    }
}
