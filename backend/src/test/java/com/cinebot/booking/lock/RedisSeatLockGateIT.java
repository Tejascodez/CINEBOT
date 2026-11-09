package com.cinebot.booking.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.cinebot.booking.lock.RedisSeatLockGate.Outcome;
import com.cinebot.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Lua scripts against a real Redis. */
class RedisSeatLockGateIT extends IntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(8);

    @Autowired
    private RedisSeatLockGate gate;

    @Test
    void acquiresAllSeatsWithTtl() {
        UUID lock = UUID.randomUUID();

        assertThat(gate.tryAcquire(7L, List.of(1L, 2L), lock, TTL).outcome()).isEqualTo(Outcome.ACQUIRED);

        assertThat(redis.opsForValue().get("cinebot:seatlock:{7}:1")).isEqualTo(lock.toString());
        assertThat(redis.getExpire("cinebot:seatlock:{7}:2")).isBetween(470L, 480L);
    }

    @Test
    void isAllOrNothingAndReportsConflicts() {
        gate.tryAcquire(7L, List.of(2L), UUID.randomUUID(), TTL);

        var result = gate.tryAcquire(7L, List.of(1L, 2L, 3L), UUID.randomUUID(), TTL);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.conflictingSeatIds()).containsExactly(2L);
        assertThat(redis.hasKey("cinebot:seatlock:{7}:1")).isFalse(); // nothing partially claimed
        assertThat(redis.hasKey("cinebot:seatlock:{7}:3")).isFalse();
    }

    @Test
    void sameLockMayReAcquireItsOwnKeys() {
        UUID lock = UUID.randomUUID();
        gate.tryAcquire(7L, List.of(1L), lock, TTL);

        assertThat(gate.tryAcquire(7L, List.of(1L), lock, TTL).outcome()).isEqualTo(Outcome.ACQUIRED);
    }

    @Test
    void releaseDeletesOnlyKeysOwnedByTheLock() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        gate.tryAcquire(7L, List.of(1L), mine, TTL);
        gate.tryAcquire(7L, List.of(2L), theirs, TTL);

        gate.release(7L, List.of(1L, 2L), mine);

        assertThat(redis.hasKey("cinebot:seatlock:{7}:1")).isFalse();
        assertThat(redis.opsForValue().get("cinebot:seatlock:{7}:2")).isEqualTo(theirs.toString());
    }

    @Test
    void keysOfDifferentShowsDoNotCollide() {
        gate.tryAcquire(7L, List.of(1L), UUID.randomUUID(), TTL);

        assertThat(gate.tryAcquire(8L, List.of(1L), UUID.randomUUID(), TTL).outcome()).isEqualTo(Outcome.ACQUIRED);
    }
}
