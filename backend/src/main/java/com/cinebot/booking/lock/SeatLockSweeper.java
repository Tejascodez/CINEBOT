package com.cinebot.booking.lock;

import com.cinebot.booking.BookingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled in tests (they call ExpiredLockSweep directly, deterministically). */
@Component
@ConditionalOnProperty(name = "cinebot.booking.sweeper.enabled", havingValue = "true", matchIfMissing = true)
class SeatLockSweeper {

    private final ExpiredLockSweep sweep;
    private final BookingProperties properties;

    SeatLockSweeper(ExpiredLockSweep sweep, BookingProperties properties) {
        this.sweep = sweep;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${cinebot.booking.sweeper.interval:PT30S}")
    void sweep() {
        int expired;
        do {
            expired = sweep.sweepBatch();
        } while (expired >= properties.sweeper().batchSize()); // drain backlog in batches, each its own tx
    }
}
