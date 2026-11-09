package com.cinebot.booking.ticket;

import com.cinebot.booking.BookingRepository;
import com.cinebot.booking.BookingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entry-gate scan: is this QR a genuine ticket for a booking that is still CONFIRMED? */
@RestController
@RequestMapping("/api/v1/admin/tickets")
@Validated
public class AdminTicketController {

    private final TicketCodec codec;
    private final BookingRepository bookings;

    public AdminTicketController(TicketCodec codec, BookingRepository bookings) {
        this.codec = codec;
        this.bookings = bookings;
    }

    public record VerifyRequest(@NotBlank @Size(max = 200) String qrPayload) {
    }

    public record VerifyResponse(boolean valid, String reference, BookingStatus status, Long showId,
                                 java.util.List<String> seats, String reason) {
    }

    @PostMapping("/verify")
    @Transactional(readOnly = true)
    public VerifyResponse verify(@jakarta.validation.Valid @RequestBody VerifyRequest request) {
        return codec.decode(request.qrPayload())
                .flatMap(t -> bookings.findById(t.bookingId()).filter(b -> b.getBookingReference().equals(t.reference())))
                .map(b -> new VerifyResponse(b.getStatus() == BookingStatus.CONFIRMED, b.getBookingReference(),
                        b.getStatus(), b.getShowId(), b.getSeats().stream().map(s -> s.getSeatLabel()).toList(),
                        b.getStatus() == BookingStatus.CONFIRMED ? null : "Booking is " + b.getStatus()))
                .orElse(new VerifyResponse(false, null, null, null, java.util.List.of(), "Invalid or forged ticket"));
    }
}
