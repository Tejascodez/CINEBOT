package com.cinebot.theatre.dto;

import com.cinebot.theatre.Theatre;
import java.math.BigDecimal;

public record TheatreSummaryResponse(Long id, String name, String brand, String city, String address,
                                     BigDecimal latitude, BigDecimal longitude) {

    public static TheatreSummaryResponse from(Theatre t) {
        return new TheatreSummaryResponse(t.getId(), t.getName(), t.getBrand(), t.getCity(), t.getAddress(),
                t.getLatitude(), t.getLongitude());
    }
}
