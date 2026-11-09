package com.cinebot.booking;

import static com.cinebot.booking.BookingStatus.CANCELLED;
import static com.cinebot.booking.BookingStatus.CONFIRMED;
import static com.cinebot.booking.BookingStatus.EXPIRED;
import static com.cinebot.booking.BookingStatus.PAYMENT_FAILED;
import static com.cinebot.booking.BookingStatus.PAYMENT_PENDING;
import static com.cinebot.booking.BookingStatus.PENDING;
import static com.cinebot.booking.BookingStatus.REFUNDED;
import static com.cinebot.booking.BookingStatus.REFUND_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.screen.SeatType;
import com.cinebot.seat.SeatLine;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

/** The transition table is the spec: every one of the 64 (from, to) pairs is checked. */
class BookingStatusTest {

    private static final Map<BookingStatus, Set<BookingStatus>> EXPECTED = Map.of(
            PENDING, EnumSet.of(PAYMENT_PENDING, CANCELLED, EXPIRED),
            PAYMENT_PENDING, EnumSet.of(CONFIRMED, REFUND_PENDING, PAYMENT_FAILED, EXPIRED, CANCELLED),
            CONFIRMED, EnumSet.of(REFUND_PENDING),
            PAYMENT_FAILED, EnumSet.of(REFUND_PENDING),
            EXPIRED, EnumSet.of(REFUND_PENDING),
            CANCELLED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED),
            REFUNDED, EnumSet.noneOf(BookingStatus.class));

    static Stream<Arguments> allPairs() {
        return Stream.of(BookingStatus.values())
                .flatMap(from -> Stream.of(BookingStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void transitionTableMatchesDesign(BookingStatus from, BookingStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(EXPECTED.get(from).contains(to));
    }

    @Test
    void noStateTransitionsToItself() {
        for (BookingStatus s : BookingStatus.values()) {
            assertThat(s.canTransitionTo(s)).as(s.name()).isFalse();
        }
    }

    @Test
    void onlyPendingStatesAreOpen() {
        assertThat(EnumSet.allOf(BookingStatus.class).stream().filter(BookingStatus::isOpen))
                .containsExactlyInAnyOrder(PENDING, PAYMENT_PENDING);
    }

    @Test
    void bookingRejectsInvalidTransitionAndKeepsState() {
        Booking booking = Booking.pending("CBTEST2345", 1L, 1L, UUID.randomUUID(),
                List.of(new SeatLine(1L, "A1", SeatType.REGULAR, 20_000)), Instant.now());

        assertThatThrownBy(() -> booking.transitionTo(CONFIRMED, Instant.now()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(booking.getStatus()).isEqualTo(PENDING);
    }

    @Test
    void pendingBookingSumsSeatPrices() {
        Booking booking = Booking.pending("CBTEST2345", 1L, 1L, UUID.randomUUID(), List.of(
                new SeatLine(1L, "A1", SeatType.REGULAR, 20_000),
                new SeatLine(2L, "F1", SeatType.PREMIUM, 30_000)), Instant.now());

        assertThat(booking.getTotalAmountPaise()).isEqualTo(50_000);
        assertThat(booking.getSeats()).extracting(BookingSeat::getSeatLabel).containsExactly("A1", "F1");
    }

    @Test
    void confirmationRecordsTimestamp() {
        Booking booking = Booking.pending("CBTEST2345", 1L, 1L, UUID.randomUUID(),
                List.of(new SeatLine(1L, "A1", SeatType.REGULAR, 20_000)), Instant.now());
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        booking.transitionTo(PAYMENT_PENDING, now);
        booking.transitionTo(CONFIRMED, now);

        assertThat(booking.getConfirmedAt()).isEqualTo(now);
        assertThat(ReflectionTestUtils.getField(booking, "closedAt")).isNull();
    }
}
