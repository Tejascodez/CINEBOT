package com.cinebot.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Storage and comparisons are in UTC (Instant / TIMESTAMPTZ). Human concepts such as "today", "tonight" or
 * "after 8 PM" are evaluated in the business time zone, otherwise a 00:30 show would land on the wrong date.
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BusinessTime() {
    }

    public static OffsetDateTime toLocal(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }

    public static LocalDate today(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
