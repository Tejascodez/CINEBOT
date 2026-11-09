package com.cinebot.screen;

import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.screen.dto.ScreenRequest;
import com.cinebot.screen.dto.ScreenResponse;
import com.cinebot.screen.dto.SeatLayoutRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScreenService {

    static final int MAX_SEATS_PER_SCREEN = 1000;

    private final ScreenRepository screens;
    private final SeatRepository seats;

    public ScreenService(ScreenRepository screens, SeatRepository seats) {
        this.screens = screens;
        this.seats = seats;
    }

    /** Caller (theatre module) has already verified the theatre exists. */
    @Transactional
    public ScreenResponse create(Long theatreId, ScreenRequest request) {
        try {
            Screen screen = screens.saveAndFlush(new Screen(theatreId, request.name().trim(), request.screenType()));
            return toResponse(screen, List.of());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.SCREEN_NAME_TAKEN);
        }
    }

    /**
     * Replaces the whole layout. Allowed only while no show uses this screen: existing show inventory references
     * the seat rows, so the delete hits an FK violation, which we turn into 409. Redesigning a screen with sold
     * tickets would silently invalidate those tickets.
     */
    @Transactional
    public ScreenResponse replaceLayout(Long screenId, SeatLayoutRequest request) {
        Screen screen = getEntity(screenId);
        validateLayout(request);
        try {
            seats.deleteByScreenId(screenId);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.SCREEN_HAS_SHOWS);
        }
        List<Seat> layout = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < request.rows().size(); rowIndex++) {
            SeatLayoutRequest.Row row = request.rows().get(rowIndex);
            for (int number = 1; number <= row.seats(); number++) {
                layout.add(new Seat(screenId, row.label(), rowIndex, number, row.type()));
            }
        }
        List<Seat> saved = seats.saveAll(layout);
        screen.setTotalSeats(saved.size());
        return toResponse(screen, saved);
    }

    @Transactional(readOnly = true)
    public ScreenResponse get(Long screenId) {
        Screen screen = getEntity(screenId);
        return toResponse(screen, seats.findByScreenIdOrderByRowIndexAscSeatNumberAsc(screenId));
    }

    @Transactional(readOnly = true)
    public List<ScreenResponse> listByTheatre(Long theatreId) {
        return screens.findByTheatreIdOrderByName(theatreId).stream()
                .map(s -> toResponse(s, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public Screen getEntity(Long screenId) {
        return screens.findById(screenId)
                .orElseThrow(() -> new ApiException(ErrorCode.SCREEN_NOT_FOUND, "Screen " + screenId + " not found"));
    }

    @Transactional(readOnly = true)
    public Set<SeatType> seatTypesOf(Long screenId) {
        return seats.findSeatTypes(screenId);
    }

    private static void validateLayout(SeatLayoutRequest request) {
        Set<String> labels = new HashSet<>();
        int total = 0;
        for (SeatLayoutRequest.Row row : request.rows()) {
            if (!labels.add(row.label())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Duplicate row label " + row.label());
            }
            total += row.seats();
        }
        if (total > MAX_SEATS_PER_SCREEN) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Layout has " + total + " seats; maximum is " + MAX_SEATS_PER_SCREEN);
        }
    }

    /** @param layout null = omit row details (list views) */
    private static ScreenResponse toResponse(Screen screen, List<Seat> layout) {
        List<ScreenResponse.RowSummary> rows = null;
        if (layout != null) {
            Map<String, ScreenResponse.RowSummary> byRow = new LinkedHashMap<>();
            for (Seat seat : layout) {
                byRow.merge(seat.getRowLabel(),
                        new ScreenResponse.RowSummary(seat.getRowLabel(), 1, seat.getSeatType()),
                        (a, b) -> new ScreenResponse.RowSummary(a.label(), a.seats() + 1, a.type()));
            }
            rows = List.copyOf(byRow.values());
        }
        return new ScreenResponse(screen.getId(), screen.getTheatreId(), screen.getName(), screen.getScreenType(),
                screen.getTotalSeats(), rows);
    }
}
