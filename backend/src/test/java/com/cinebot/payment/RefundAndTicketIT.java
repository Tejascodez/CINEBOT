package com.cinebot.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.lock.dto.LockSeatsRequest;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.support.PaymentTestSupport;
import com.cinebot.user.Role;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Cancelling a paid booking (refund + resale) and ticket verification at the gate. */
class RefundAndTicketIT extends PaymentTestSupport {

    @Test
    void cancellingAConfirmedBookingRefundsAndPutsSeatsBackOnSale() throws Exception {
        BookingResponse booking = confirmed(alice, "A1");

        mvc.perform(post("/api/v1/bookings/{id}/cancel", booking.id()).header("Authorization", bearerFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.ticket").doesNotExist());
        assertThat(seatStatus("A1")).isEqualTo("AVAILABLE");
        assertThat(paymentStatus(booking.id())).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM payments", String.class)).startsWith("rfnd_");

        provider.processDueRefunds();
        assertThat(deliverWebhooks()).containsExactly("PROCESSED");
        assertThat(bookingStatus(booking.id())).isEqualTo("REFUNDED");

        // resale: bob buys the same seat; the "confirmed once" index allows it because alice's row was released
        BookingResponse resold = confirmed(bob, "A1");
        assertThat(bookingStatus(resold.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT booking_id FROM show_seats WHERE id = ?", Long.class, seat(show, "A1")))
                .isEqualTo(resold.id());
    }

    @Test
    void cancellationClosesTwoHoursBeforeTheShow() throws Exception {
        long soonShow = show(movie("Soon", 90), screenWithLayout(theatre("INOX", "Chennai"), "S1"),
                OffsetDateTime.now().plusMinutes(90)).id();
        var lock = seatLocks.lock(alice, new LockSeatsRequest(soonShow, List.of(seat(soonShow, "A1"))));
        var booking = bookingService.create(alice, lock.lockId()).booking();
        pay(order(alice, booking));
        deliverWebhooks();

        assertThatThrownBy(() -> bookingService.cancel(alice, booking.id()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.CANCELLATION_WINDOW_CLOSED);
        assertThat(bookingStatus(booking.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void ticketVerifiesAtTheGateOnlyWhileConfirmed() throws Exception {
        BookingResponse booking = confirmed(alice, "A1", "A2");
        String qr = bookingService.get(alice, booking.id()).ticket().qrPayload();
        String admin = bearer(Role.ADMIN);

        scan(admin, qr).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reference").value(booking.reference()))
                .andExpect(jsonPath("$.seats[*]").value(org.hamcrest.Matchers.contains("A1", "A2")));

        String forged = qr.substring(0, qr.lastIndexOf('.') + 1) + "A".repeat(22);
        scan(admin, forged).andExpect(jsonPath("$.valid").value(false));
        String otherBooking = qr.replace("." + booking.id() + ".", "." + (booking.id() + 1) + ".");
        scan(admin, otherBooking).andExpect(jsonPath("$.valid").value(false));

        bookingService.cancel(alice, booking.id());
        scan(admin, qr).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"));

        scan(bearerFor(alice), qr).andExpect(status().isForbidden()); // gate staff only
    }

    private BookingResponse confirmed(com.cinebot.auth.AuthenticatedUser user, String... labels) throws Exception {
        BookingResponse booking = book(user, labels);
        pay(order(user, booking));
        deliverWebhooks();
        return booking;
    }

    private org.springframework.test.web.servlet.ResultActions scan(String auth, String qr) throws Exception {
        return mvc.perform(post("/api/v1/admin/tickets/verify").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qrPayload\":\"" + qr + "\"}"));
    }
}
