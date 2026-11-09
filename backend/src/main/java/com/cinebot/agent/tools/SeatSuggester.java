package com.cinebot.agent.tools;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.ShowSeatStatus;
import com.cinebot.seat.dto.SeatMapResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks good seats for "two tickets" so the user does not have to name seat labels. Deterministic, no AI:
 * the model asks for a suggestion, the backend computes it from the live seat map.
 * <p>
 * Preference: N adjacent available seats in one row, of the requested type; rows about 60% of the way back
 * (the usual best view) first, then the block closest to the row centre.
 */
final class SeatSuggester {

    record Suggestion(List<SeatMapResponse.SeatView> seats, boolean adjacent) {

        long totalPaise() {
            return seats.stream().mapToLong(SeatMapResponse.SeatView::pricePaise).sum();
        }

        List<String> labels() {
            return seats.stream().map(SeatMapResponse.SeatView::label).toList();
        }
    }

    private SeatSuggester() {
    }

    static Optional<Suggestion> suggest(SeatMapResponse map, int count, SeatType type) {
        List<SeatMapResponse.Row> rows = map.rows();
        double idealRow = (rows.size() - 1) * 0.6;
        Suggestion best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 0; r < rows.size(); r++) {
            List<SeatMapResponse.SeatView> seats = rows.get(r).seats().stream()
                    .sorted(Comparator.comparingInt(SeatMapResponse.SeatView::number)).toList();
            if (seats.isEmpty()) {
                continue;
            }
            double rowCentre = (seats.getFirst().number() + seats.getLast().number()) / 2.0;
            for (int start = 0; start + count <= seats.size(); start++) {
                List<SeatMapResponse.SeatView> block = seats.subList(start, start + count);
                if (!usable(block, type) || !consecutive(block)) {
                    continue;
                }
                double blockCentre = (block.getFirst().number() + block.getLast().number()) / 2.0;
                double score = Math.abs(r - idealRow) * 100 + Math.abs(blockCentre - rowCentre);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Suggestion(List.copyOf(block), true);
                }
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        // No adjacent block: best individual seats, so the user can still decide (flagged adjacent=false).
        List<SeatMapResponse.SeatView> loose = new ArrayList<>();
        for (int r : rowsByPreference(rows.size(), idealRow)) {
            for (var seat : rows.get(r).seats()) {
                if (loose.size() < count && usable(List.of(seat), type)) {
                    loose.add(seat);
                }
            }
        }
        return loose.size() == count ? Optional.of(new Suggestion(List.copyOf(loose), false)) : Optional.empty();
    }

    private static boolean usable(List<SeatMapResponse.SeatView> seats, SeatType type) {
        return seats.stream().allMatch(s -> s.status() == ShowSeatStatus.AVAILABLE && (type == null || s.type() == type))
                && seats.stream().map(SeatMapResponse.SeatView::type).distinct().count() == 1;
    }

    private static boolean consecutive(List<SeatMapResponse.SeatView> block) {
        for (int i = 1; i < block.size(); i++) {
            if (block.get(i).number() != block.get(i - 1).number() + 1) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> rowsByPreference(int rowCount, double idealRow) {
        List<Integer> order = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            order.add(r);
        }
        order.sort(Comparator.comparingDouble(r -> Math.abs(r - idealRow)));
        return order;
    }
}
