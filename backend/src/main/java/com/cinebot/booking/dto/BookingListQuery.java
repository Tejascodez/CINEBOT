package com.cinebot.booking.dto;

import com.cinebot.booking.BookingStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BookingListQuery(BookingStatus status, @Min(0) Integer page, @Min(1) @Max(50) Integer size) {

    public BookingListQuery {
        page = page == null ? 0 : page;
        size = size == null ? 10 : size;
    }
}
