package com.cinebot.seat;

/** LOCKED and BOOKED are driven by the booking engine (Step 4); BLOCKED = not sellable (cancelled show, broken seat). */
public enum ShowSeatStatus {
    AVAILABLE,
    LOCKED,
    BOOKED,
    BLOCKED
}
