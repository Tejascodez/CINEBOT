package com.cinebot.booking.lock;

import com.cinebot.booking.BookingProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cleanup of expired locks: frees seats, expires open bookings. Correctness does not depend on it (expired
 * locks are already treated as free by reads and by new lock attempts); it keeps the data tidy and
 * moves bookings to EXPIRED.
 */
@Component
public class ExpiredLockSweep {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLockSweep.class);

    private final SeatLockRepository locks;
    private final SeatLockLifecycle lifecycle;
    private final BookingProperties properties;
    private final Clock clock;

    public ExpiredLockSweep(SeatLockRepository locks, SeatLockLifecycle lifecycle, BookingProperties properties,
                            Clock clock) {
        this.locks = locks;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
    }

    /** One batch in one short transaction. @return number of locks expired */
    @Transactional
    public int sweepBatch() {
        Instant now = clock.instant();
        List<SeatLock> due = locks.claimExpired(now, now.minus(properties.paymentGrace()),
                properties.sweeper().batchSize());
        due.forEach(lock -> lifecycle.expire(lock, now));
        if (!due.isEmpty()) {
            log.info("Expired {} seat locks", due.size());
        }
        return due.size();
    }
}
