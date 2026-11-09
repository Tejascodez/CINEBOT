package com.cinebot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cinebot.auth.dto.AuthResponse;
import com.cinebot.auth.dto.LoginRequest;
import com.cinebot.auth.dto.RegisterRequest;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.user.Role;
import com.cinebot.user.User;
import com.cinebot.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    private final UserService userService = mock(UserService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));
        authService = new AuthService(userService, refreshTokens, jwtService, encoder);
        when(jwtService.generateAccessToken(any())).thenReturn("access");
        when(jwtService.accessTokenTtlSeconds()).thenReturn(900L);
        when(refreshTokens.issueNewFamily(any())).thenReturn("refresh");
    }

    @Test
    void registerHashesPasswordAndAlwaysAssignsUserRole() {
        User created = user(7L, "new@cinebot.dev", "hashed:Secret123");
        when(userService.createUser(eq("New@CineBot.dev"), eq("hashed:Secret123"), eq("New"), eq(null), eq(Role.USER)))
                .thenReturn(created);

        AuthResponse response = authService.register(
                new RegisterRequest("New@CineBot.dev", "Secret123", "New", null));

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.user().role()).isEqualTo(Role.USER);
    }

    @Test
    void loginWithUnknownEmailStillRunsPasswordCheckAndFails() {
        when(userService.findByEmail("ghost@cinebot.dev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@cinebot.dev", "whatever1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        // Timing equalisation: a hash comparison happens even though no user exists.
        verify(encoder).matches(eq("whatever1"), anyString());
        verify(refreshTokens, never()).issueNewFamily(any());
    }

    @Test
    void loginWithWrongPasswordFailsWithSameError() {
        User user = user(1L, "a@cinebot.dev", "stored-hash");
        when(userService.findByEmail("a@cinebot.dev")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong-pass", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@cinebot.dev", "wrong-pass")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginWithCorrectPasswordIssuesTokens() {
        User user = user(1L, "a@cinebot.dev", "stored-hash");
        when(userService.findByEmail("a@cinebot.dev")).thenReturn(Optional.of(user));
        when(encoder.matches("right-pass", "stored-hash")).thenReturn(true);

        AuthResponse response = authService.login(new LoginRequest("a@cinebot.dev", "right-pass"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);
        assertThat(response.user().id()).isEqualTo(1L);
    }

    private static User user(Long id, String email, String hash) {
        User user = new User(email, hash, "Test", null, Role.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
