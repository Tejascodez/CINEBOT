package com.cinebot.theatre.dto;

import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.theatre.Theatre;
import java.math.BigDecimal;
import java.util.List;

public record TheatreDetailResponse(Long id, String name, String brand, String city, String address,
                                    BigDecimal latitude, BigDecimal longitude, boolean active,
                                    List<ScreenResponse> screens) {

    public static TheatreDetailResponse from(Theatre t, List<ScreenResponse> screens) {
        return new TheatreDetailResponse(t.getId(), t.getName(), t.getBrand(), t.getCity(), t.getAddress(),
                t.getLatitude(), t.getLongitude(), t.isActive(), screens);
    }
}
