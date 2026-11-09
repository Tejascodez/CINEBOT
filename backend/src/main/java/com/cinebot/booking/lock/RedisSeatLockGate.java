package com.cinebot.booking.lock;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fast admission filter in front of PostgreSQL. When 20 users race for one seat, Redis turns away 19 in
 * about a millisecond, so they never reach (and contend on) the database row.
 *
 * <p><b>Not a source of truth.</b> PostgreSQL decides who holds a seat. This gate therefore FAILS OPEN: if
 * Redis errors or times out, requests bypass it and correctness still comes from the DB. A tiny circuit
 * breaker stops every request from paying the Redis timeout while Redis is down.
 *
 * <p>Keys: {@code cinebot:seatlock:{showId}:showSeatId}. The {showId} hash tag puts all of a show's keys in
 * one Redis Cluster slot, which a multi-key Lua script requires.
 */
@Component
public class RedisSeatLockGate {

    public enum Outcome { ACQUIRED, CONFLICT, BYPASSED }

    /** @param conflictingSeatIds seats currently claimed by another lock (only for CONFLICT) */
    public record Result(Outcome outcome, List<Long> conflictingSeatIds) {
        static final Result ACQUIRED = new Result(Outcome.ACQUIRED, List.of());
        static final Result BYPASSED = new Result(Outcome.BYPASSED, List.of());
    }

    private static final Logger log = LoggerFactory.getLogger(RedisSeatLockGate.class);
    static final Duration BREAKER_OPEN_FOR = Duration.ofSeconds(30);

    /**
     * All-or-nothing: if any seat is held by a different lock, set nothing and return the 1-based positions of
     * the conflicting keys; an empty result means acquired. Atomic because Redis runs a script single-threaded.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local conflicts = {}
            for i, key in ipairs(KEYS) do
              local holder = redis.call('GET', key)
              if holder and holder ~= ARGV[1] then table.insert(conflicts, i) end
            end
            if #conflicts > 0 then return conflicts end
            for _, key in ipairs(KEYS) do
              redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return {}
            """, List.class);

    /** Compare-and-delete: never removes a key that now belongs to someone else's lock. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local released = 0
            for _, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then
                redis.call('DEL', key)
                released = released + 1
              end
            end
            return released
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private volatile long breakerOpenUntilMillis;

    public RedisSeatLockGate(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Result tryAcquire(Long showId, List<Long> showSeatIds, UUID lockId, Duration ttl) {
        if (breakerOpen()) {
            return Result.BYPASSED;
        }
        try {
            List<?> conflicts = redis.execute(ACQUIRE, keys(showId, showSeatIds), lockId.toString(),
                    String.valueOf(ttl.toMillis()));
            if (conflicts == null || conflicts.isEmpty()) {
                return Result.ACQUIRED;
            }
            List<Long> seatIds = conflicts.stream()
                    .map(pos -> showSeatIds.get(((Number) pos).intValue() - 1))
                    .toList();
            return new Result(Outcome.CONFLICT, seatIds);
        } catch (RuntimeException e) {
            trip(e);
            return Result.BYPASSED;
        }
    }

    public void release(Long showId, Collection<Long> showSeatIds, UUID lockId) {
        if (showSeatIds.isEmpty() || breakerOpen()) {
            return; // keys also expire on their own (TTL = lock TTL)
        }
        try {
            redis.execute(RELEASE, keys(showId, showSeatIds), lockId.toString());
        } catch (RuntimeException e) {
            trip(e);
        }
    }

    /** Operational/test hook: close the breaker immediately (e.g. after Redis is known to be back). */
    public void resetBreaker() {
        breakerOpenUntilMillis = 0;
    }

    public static String key(Long showId, Long showSeatId) {
        return "cinebot:seatlock:{" + showId + "}:" + showSeatId;
    }

    private static List<String> keys(Long showId, Collection<Long> showSeatIds) {
        return showSeatIds.stream().map(id -> key(showId, id)).toList();
    }

    private boolean breakerOpen() {
        return clock.millis() < breakerOpenUntilMillis;
    }

    private void trip(RuntimeException e) {
        breakerOpenUntilMillis = clock.millis() + BREAKER_OPEN_FOR.toMillis();
        log.warn("Redis seat-lock gate unavailable ({}); bypassing for {}s, PostgreSQL remains authoritative",
                e.getClass().getSimpleName(), BREAKER_OPEN_FOR.toSeconds());
    }
}
