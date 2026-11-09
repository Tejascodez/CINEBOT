package com.cinebot.seat;

import com.cinebot.screen.SeatType;

/** A show seat as the booking engine needs it: id, human label, type, price. */
public record SeatLine(Long showSeatId, String label, SeatType type, long pricePaise) {
}
