package com.cinebot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.cinebot.screen.SeatType;
import com.cinebot.seat.ShowSeatStatus;
import com.cinebot.seat.dto.SeatMapResponse;
import com.cinebot.seat.dto.SeatMapResponse.SeatView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeatSuggesterTest {

    @Test
    void prefersAdjacentCentralSeatsAboutSixtyPercentBack() {
        var map = map(Set.of(), "A", "B", "C", "D", "E", "F"); // 6 rows x 10 seats, all free
        var s = SeatSuggester.suggest(map, 2, null).orElseThrow();
        assertThat(s.labels()).containsExactly("D5", "D6"); // row index 3 = 60% of 5, centre of the row
        assertThat(s.adjacent()).isTrue();
        assertThat(s.totalPaise()).isEqualTo(40_000);
    }

    @Test
    void skipsTakenSeatsAndGapsInNumbering() {
        var map = map(Set.of("D5", "D6", "C5", "E5"), "A", "B", "C", "D", "E", "F");
        var s = SeatSuggester.suggest(map, 2, null).orElseThrow();
        assertThat(s.labels()).doesNotContain("D5", "D6");
        assertThat(s.labels().getFirst()).startsWith("D"); // still the preferred row, next-best block
        assertThat(s.adjacent()).isTrue();
    }

    @Test
    void fallsBackToSeparateSeatsWhenNoBlockIsFree() {
        // only every other seat free in the single row
        var map = map(Set.of("A2", "A4", "A6", "A8", "A10"), "A");
        var s = SeatSuggester.suggest(map, 3, null).orElseThrow();
        assertThat(s.adjacent()).isFalse();
        assertThat(s.labels()).hasSize(3).allMatch(l -> List.of("A1", "A3", "A5", "A7", "A9").contains(l));
    }

    @Test
    void respectsSeatTypeAndReportsWhenImpossible() {
        var map = map(Set.of(), "A", "B");
        assertThat(SeatSuggester.suggest(map, 2, SeatType.RECLINER)).isEmpty();
        assertThat(SeatSuggester.suggest(map, 21, null)).isEmpty();
    }

    private static SeatMapResponse map(Set<String> taken, String... rows) {
        List<SeatMapResponse.Row> result = new ArrayList<>();
        long id = 1;
        int free = 0;
        for (String row : rows) {
            List<SeatView> seats = new ArrayList<>();
            for (int n = 1; n <= 10; n++) {
                String label = row + n;
                boolean available = !taken.contains(label);
                free += available ? 1 : 0;
                seats.add(new SeatView(id++, label, n, SeatType.REGULAR, 20_000,
                        available ? ShowSeatStatus.AVAILABLE : ShowSeatStatus.BOOKED));
            }
            result.add(new SeatMapResponse.Row(row, seats));
        }
        return new SeatMapResponse(1L, rows.length * 10, free, result);
    }
}
