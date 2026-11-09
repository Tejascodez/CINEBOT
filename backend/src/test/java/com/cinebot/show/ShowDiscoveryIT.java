package com.cinebot.show;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.show.dto.ShowResponse;
import com.cinebot.support.VenueTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Public discovery: show search by city/movie/date/time (IST), seat maps, "now showing in city". */
class ShowDiscoveryIT extends VenueTestSupport {

    @Autowired
    private MockMvc mvc;

    private long interstellar;
    private long idiots;
    private long chennaiScreen;
    private long mumbaiScreen;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        interstellar = movie("Interstellar", 100);
        idiots = movie("3 Idiots", 100);
        movie("Not Scheduled Anywhere", 100);
        chennaiScreen = screenWithLayout(theatre("PVR Velachery", "Chennai"), "S1");
        mumbaiScreen = screenWithLayout(theatre("PVR Lower Parel", "Mumbai"), "S1");
        day = tomorrow();
    }

    @Test
    void findsShowsForMovieInCityOnDate() throws Exception {
        show(interstellar, chennaiScreen, ist(day, 10, 0));
        show(interstellar, chennaiScreen, ist(day, 19, 30));
        show(idiots, chennaiScreen, ist(day, 14, 0));
        show(interstellar, mumbaiScreen, ist(day, 19, 30));
        show(interstellar, chennaiScreen, ist(day.plusDays(1), 10, 0));

        mvc.perform(get("/api/v1/shows").param("movieId", String.valueOf(interstellar))
                        .param("city", "chennai").param("date", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].startTime").value(contains(
                        ist(day, 10, 0).toLocalDateTime() + ":00+05:30", ist(day, 19, 30).toLocalDateTime() + ":00+05:30")))
                .andExpect(jsonPath("$[0].theatreName").value("PVR Velachery"))
                .andExpect(jsonPath("$[0].availableSeats").value(5))
                .andExpect(jsonPath("$[0].minPricePaise").value(20000))
                .andExpect(jsonPath("$[0].prices").doesNotExist());
    }

    @Test
    void timeWindowAfterEightPm() throws Exception {
        show(interstellar, chennaiScreen, ist(day, 17, 0));
        show(interstellar, chennaiScreen, ist(day, 20, 15));

        mvc.perform(get("/api/v1/shows").param("city", "Chennai").param("date", day.toString()).param("from", "20:00"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].startTime").value(ist(day, 20, 15).toLocalDateTime() + ":00+05:30"));
    }

    @Test
    void dateBoundaryIsMidnightIstNotUtc() throws Exception {
        show(interstellar, chennaiScreen, ist(day, 21, 0));               // 15:30 UTC same day
        show(interstellar, mumbaiScreen, ist(day.plusDays(1), 0, 30));   // 19:00 UTC of `day` -> belongs to next date

        mvc.perform(get("/api/v1/shows").param("movieId", String.valueOf(interstellar)).param("date", day.toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].city").value("Chennai"));
        mvc.perform(get("/api/v1/shows").param("movieId", String.valueOf(interstellar))
                        .param("date", day.plusDays(1).toString()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].city").value("Mumbai"));
    }

    @Test
    void cancelledShowsAndInactiveTheatresAreHidden() throws Exception {
        ShowResponse cancelled = show(interstellar, chennaiScreen, ist(day, 10, 0));
        showService.cancel(cancelled.id());
        show(interstellar, mumbaiScreen, ist(day, 10, 0));
        jdbc.update("UPDATE theatres SET active = false WHERE city = 'Mumbai'");

        mvc.perform(get("/api/v1/shows").param("movieId", String.valueOf(interstellar)).param("date", day.toString()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void availableSeatCountReflectsInventory() throws Exception {
        ShowResponse s = show(interstellar, chennaiScreen, ist(day, 10, 0));
        jdbc.update("UPDATE show_seats SET status = 'BLOCKED' WHERE id IN (SELECT id FROM show_seats ORDER BY id LIMIT 2)");

        mvc.perform(get("/api/v1/shows/{id}", s.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(5))
                .andExpect(jsonPath("$.availableSeats").value(3))
                .andExpect(jsonPath("$.prices[*].seatType").value(contains("REGULAR", "PREMIUM")));
    }

    @Test
    void seatMapIsGroupedByRowInLayoutOrder() throws Exception {
        ShowResponse s = show(interstellar, chennaiScreen, ist(day, 10, 0));
        jdbc.update("UPDATE show_seats SET status = 'BLOCKED' WHERE id = (SELECT min(id) FROM show_seats)");

        mvc.perform(get("/api/v1/shows/{id}/seats", s.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(5))
                .andExpect(jsonPath("$.availableSeats").value(4))
                .andExpect(jsonPath("$.rows[*].label").value(contains("A", "B")))
                .andExpect(jsonPath("$.rows[0].seats[*].label").value(contains("A1", "A2", "A3")))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.rows[0].seats[0].showSeatId").isNumber())
                .andExpect(jsonPath("$.rows[1].seats[0].type").value("PREMIUM"))
                .andExpect(jsonPath("$.rows[1].seats[0].pricePaise").value(35000));
    }

    @Test
    void moviesCanBeFilteredToThoseShowingInACity() throws Exception {
        show(interstellar, chennaiScreen, ist(day, 10, 0));
        show(idiots, mumbaiScreen, ist(day, 10, 0));

        mvc.perform(get("/api/v1/movies").param("city", "CHENNAI"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Interstellar"));
        mvc.perform(get("/api/v1/movies").param("city", "Pune"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/movies"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void searchMustBeScopedAndTimeRangeValid() throws Exception {
        mvc.perform(get("/api/v1/shows"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("scoped")));
        mvc.perform(get("/api/v1/shows").param("city", "Chennai").param("from", "22:00").param("to", "20:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("timeRangeValid")));
        mvc.perform(get("/api/v1/shows").param("city", "Chennai").param("date", "05-11-2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownShowIs404() throws Exception {
        mvc.perform(get("/api/v1/shows/{id}", 987654)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
        mvc.perform(get("/api/v1/shows/{id}/seats", 987654)).andExpect(status().isNotFound());
    }
}
