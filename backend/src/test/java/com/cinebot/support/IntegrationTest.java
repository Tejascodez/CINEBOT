package com.cinebot.support;

import com.cinebot.auth.JwtService;
import com.cinebot.movie.tmdb.TmdbClient;
import com.cinebot.user.Role;
import com.cinebot.user.User;
import com.cinebot.user.UserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base class: full Spring context + one shared Postgres container; every table truncated before each test.
 * TmdbClient is mocked here (not per test class) so all ITs share ONE application context and container,
 * and no test can ever call the real TMDB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ScriptedChatModelConfig.class})
public abstract class IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserService userService;
    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    protected com.cinebot.booking.lock.RedisSeatLockGate seatLockGate;

    @MockitoBean
    protected TmdbClient tmdbClient;

    @BeforeEach
    void cleanDatabase() {
        List<String> tables = jdbc.queryForList("""
                SELECT quote_ident(tablename) FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
                c.serverCommands().flushAll();
                return null;
            });
        } catch (RuntimeException redisDown) {
            // RedisOutageIT runs without Redis on purpose
        }
        seatLockGate.resetBreaker(); // a trip in one test must not leak into the next
    }

    protected String bearer(Role role) {
        User user = userService.createUser(role.name().toLowerCase() + System.nanoTime() + "@test.dev",
                "not-used", "Test " + role, null, role);
        return "Bearer " + jwtService.generateAccessToken(user);
    }
}
