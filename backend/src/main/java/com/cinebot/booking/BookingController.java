package com.cinebot.booking;

import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.booking.dto.BookingListQuery;
import com.cinebot.booking.dto.BookingResponse;
import com.cinebot.booking.dto.CreateBookingRequest;
import com.cinebot.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** 201 when created; 200 with the same booking when the request is repeated for the same lock. */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        BookingService.CreatedBooking result = bookingService.create(user, request.lockId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.booking());
    }

    @GetMapping("/my")
    public PageResponse<BookingResponse> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @ModelAttribute BookingListQuery query) {
        return bookingService.listMine(user, query);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return bookingService.get(user, id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return bookingService.cancel(user, id);
    }
}
