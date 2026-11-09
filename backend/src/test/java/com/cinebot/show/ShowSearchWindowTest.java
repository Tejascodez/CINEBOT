package com.cinebot.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cinebot.show.dto.ShowSearchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The IST date/time window -> UTC instant translation behind GET /shows. */
class ShowSearchWindowTest {

    // 2026-11-05 10:00 IST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T04:30:00Z"), ZoneOffset.UTC);

    private final ShowQueryRepository queries = mock(ShowQueryRepository.class);
    private final ShowService service = new ShowService(null, queries, null, null, null, null, CLOCK);

    @Test
    void futureDateCoversWholeIstDay() {
        service.search(new ShowSearchQuery(1L, null, null, LocalDate.of(2026, 11, 6), null, null));

        // 2026-11-06 00:00 IST = 2026-11-05 18:30 UTC
        verify(queries).search(eq(1L), isNull(), isNull(),
                eq(Instant.parse("2026-11-05T18:30:00Z")), eq(Instant.parse("2026-11-06T18:30:00Z")));
    }

    @Test
    void todayStartsNowSoStartedShowsAreExcluded() {
        service.search(new ShowSearchQuery(null, null, "Chennai", null, null, null));

        verify(queries).search(isNull(), isNull(), eq("Chennai"),
                eq(Instant.parse("2026-11-05T04:30:00Z")), eq(Instant.parse("2026-11-05T18:30:00Z")));
    }

    @Test
    void afterEightPmTonight() {
        service.search(new ShowSearchQuery(null, null, " Chennai ", null, LocalTime.of(20, 0), null));

        // 20:00 IST = 14:30 UTC; city trimmed
        verify(queries).search(isNull(), isNull(), eq("Chennai"),
                eq(Instant.parse("2026-11-05T14:30:00Z")), eq(Instant.parse("2026-11-05T18:30:00Z")));
    }

    @Test
    void windowEntirelyInThePastSkipsTheQuery() {
        var result = service.search(new ShowSearchQuery(1L, null, null, null, LocalTime.of(6, 0), LocalTime.of(9, 0)));

        assertThat(result).isEmpty();
        verifyNoInteractions(queries);
    }

    @Test
    void pastDateReturnsNothing() {
        assertThat(service.search(new ShowSearchQuery(1L, null, null, LocalDate.of(2026, 11, 1), null, null))).isEmpty();
        verify(queries, org.mockito.Mockito.never()).search(any(), any(), any(), any(), any());
    }
}
