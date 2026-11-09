package com.cinebot.booking.lock;

/** ACTIVE holds seats; RELEASED (user/cancel), EXPIRED (TTL), CONSUMED (booking confirmed, Step 5) are final. */
public enum SeatLockStatus {
    ACTIVE,
    RELEASED,
    EXPIRED,
    CONSUMED
}
