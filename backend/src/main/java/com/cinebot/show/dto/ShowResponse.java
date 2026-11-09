package com.cinebot.show.dto;

import com.cinebot.screen.ScreenType;
import com.cinebot.screen.SeatType;
import com.cinebot.show.ShowStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** Times are returned with the business offset (+05:30): unambiguous, and directly readable by people and the chatbot. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShowResponse(
        Long id,
        Long movieId,
        String movieTitle,
        Long theatreId,
        String theatreName,
        String city,
        Long screenId,
        String screenName,
        ScreenType screenType,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        String language,
        String format,
        ShowStatus status,
        int totalSeats,
        int availableSeats,
        Long minPricePaise,
        List<Price> prices) {

    public record Price(SeatType seatType, long pricePaise) {
    }

    public ShowResponse withPrices(List<Price> newPrices) {
        return new ShowResponse(id, movieId, movieTitle, theatreId, theatreName, city, screenId, screenName, screenType,
                startTime, endTime, language, format, status, totalSeats, availableSeats, minPricePaise, newPrices);
    }
}
