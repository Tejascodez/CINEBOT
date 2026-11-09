package com.cinebot.seat;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.dto.SeatMapResponse;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowSeatService {

    private final ShowSeatRepository showSeats;
    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;

    public ShowSeatService(ShowSeatRepository showSeats, NamedParameterJdbcTemplate jdbc, java.time.Clock clock) {
        this.showSeats = showSeats;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Copies the screen's physical seats into per-show inventory with prices, in ONE set-based statement
     * (vs. hundreds of entity inserts). MANDATORY: must run inside the caller's transaction that created the
     * show, so a show never exists without its inventory. A missing price yields NULL, which the NOT NULL
     * column rejects: defense in depth behind the service-level pricing validation.
     *
     * @return number of inventory rows created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int createInventory(Long showId, Long screenId, Map<SeatType, Long> prices) {
        var params = new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("screenId", screenId)
                .addValue("regular", prices.get(SeatType.REGULAR), Types.BIGINT)
                .addValue("premium", prices.get(SeatType.PREMIUM), Types.BIGINT)
                .addValue("recliner", prices.get(SeatType.RECLINER), Types.BIGINT);
        return jdbc.update("""
                INSERT INTO show_seats (show_id, seat_id, price_paise, status)
                SELECT :showId, s.id,
                       CASE s.seat_type WHEN 'REGULAR'  THEN :regular
                                        WHEN 'PREMIUM'  THEN :premium
                                        WHEN 'RECLINER' THEN :recliner END,
                       'AVAILABLE'
                FROM seats s
                WHERE s.screen_id = :screenId
                """, params);
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long showId) {
        Map<String, List<SeatMapResponse.SeatView>> rows = new LinkedHashMap<>();
        int available = 0;
        int total = 0;
        var now = clock.instant();
        for (ShowSeat ss : showSeats.findSeatMap(showId)) {
            var seat = ss.getSeat();
            ShowSeatStatus status = ss.effectiveStatus(now);
            rows.computeIfAbsent(seat.getRowLabel(), k -> new ArrayList<>())
                    .add(new SeatMapResponse.SeatView(ss.getId(), seat.getLabel(), seat.getSeatNumber(),
                            seat.getSeatType(), ss.getPricePaise(), status));
            total++;
            if (status == ShowSeatStatus.AVAILABLE) {
                available++;
            }
        }
        List<SeatMapResponse.Row> rowList = rows.entrySet().stream()
                .map(e -> new SeatMapResponse.Row(e.getKey(), e.getValue()))
                .toList();
        return new SeatMapResponse(showId, total, available, rowList);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasHeldOrSoldSeats(Long showId) {
        return showSeats.countByShowIdAndStatusIn(showId, EnumSet.of(ShowSeatStatus.LOCKED, ShowSeatStatus.BOOKED)) > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int blockAllAvailable(Long showId) {
        return showSeats.blockAvailable(showId);
    }
}
