package com.cinebot.booking;

import com.cinebot.outbox.OutboxWriter;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONE way services change a booking's status. It applies the state machine and appends the matching event
 * to the outbox in the same transaction, so no call site can change state without publishing (or publish a
 * change that rolled back).
 */
@Component
public class BookingTransitions {

    private final OutboxWriter outbox;

    public BookingTransitions(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void move(Booking booking, BookingStatus next, Instant now) {
        BookingStatus from = booking.getStatus();
        booking.transitionTo(next, now); // throws on invalid transitions: then nothing is published
        publish(booking, from, BookingEvent.typeFor(next), now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Booking booking, Instant now) {
        publish(booking, null, BookingEvent.CREATED, now);
    }

    private void publish(Booking booking, BookingStatus from, String type, Instant now) {
        outbox.append(BookingEvent.AGGREGATE, booking.getId(), type, new BookingEvent(booking.getId(),
                booking.getBookingReference(), booking.getUserId(), booking.getShowId(), from, booking.getStatus(),
                booking.getTotalAmountPaise(), booking.getSeats().stream().map(BookingSeat::getSeatLabel).toList(), now));
    }
}
