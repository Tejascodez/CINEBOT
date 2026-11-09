package com.cinebot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cinebot.auth.dto.RefreshRequest;
import com.cinebot.auth.dto.RegisterRequest;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Many requests refresh the SAME token at the same instant (double-click, retrying client, or attacker racing
 * the victim). Without the row lock, several could read "not revoked" and each mint a valid new token.
 */
class RefreshTokenConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 10;

    @Autowired
    private AuthService authService;

    @Test
    void concurrentRefreshOfSameTokenHasExactlyOneWinner() throws Exception {
        String token = authService.register(
                new RegisterRequest("race@example.com", "Password123", "Racer", null)).refreshToken();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> winners = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    winners.add(authService.refresh(new RefreshRequest(token)).refreshToken());
                } catch (ApiException e) {
                    failures.add(e.code());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(winners).hasSize(1);
        assertThat(failures).hasSize(THREADS - 1).containsOnly(ErrorCode.REFRESH_TOKEN_REUSED);

        // The losers' reuse detection revoked the family, so the winner's new token is dead too: fail closed.
        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(winners.peek())))
                .isInstanceOf(ApiException.class);
        Integer active = jdbc.queryForObject(
                "select count(*) from refresh_tokens where revoked_at is null", Integer.class);
        assertThat(active).isZero();
    }
}
