package com.cinebot.theatre;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.support.VenueTestSupport;
import com.cinebot.theatre.dto.TheatreRequest;
import com.cinebot.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Theatre / screen / layout administration and public theatre browsing. */
class VenueAdminIT extends VenueTestSupport {

    private static final String THEATRE = """
            {"name":"PVR Velachery","brand":"PVR","city":"Chennai","address":"Phoenix MarketCity",
             "latitude":12.9917,"longitude":80.2175}""";

    @Autowired
    private MockMvc mvc;
    private String admin;

    @BeforeEach
    void setUp() {
        admin = bearer(Role.ADMIN);
    }

    @Test
    void adminCreatesTheatreScreenAndLayout() throws Exception {
        long theatreId = id(adminPost("/api/v1/admin/theatres", THEATRE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("Chennai")));
        long screenId = id(adminPost("/api/v1/admin/theatres/" + theatreId + "/screens",
                "{\"name\":\"Audi 1\",\"screenType\":\"IMAX\"}").andExpect(status().isCreated()));

        adminPut("/api/v1/admin/screens/" + screenId + "/layout", """
                {"rows":[{"label":"A","seats":10,"type":"REGULAR"},{"label":"B","seats":6,"type":"RECLINER"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(16))
                .andExpect(jsonPath("$.rows[*].label").value(contains("A", "B")))
                .andExpect(jsonPath("$.rows[1].type").value("RECLINER"));

        mvc.perform(get("/api/v1/theatres/{id}", theatreId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screens[0].name").value("Audi 1"))
                .andExpect(jsonPath("$.screens[0].totalSeats").value(16));
    }

    @Test
    void layoutCanBeReplacedWhileNoShowsExist() throws Exception {
        long screenId = screenWithLayout(theatre("T", "Chennai"), "S1");

        adminPut("/api/v1/admin/screens/" + screenId + "/layout",
                "{\"rows\":[{\"label\":\"A\",\"seats\":4,\"type\":\"REGULAR\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(4));
    }

    @Test
    void duplicateTheatreInSameCityIsRejectedCaseInsensitively() throws Exception {
        adminPost("/api/v1/admin/theatres", THEATRE).andExpect(status().isCreated());
        adminPost("/api/v1/admin/theatres", THEATRE.replace("PVR Velachery", "pvr velachery")
                .replace("\"Chennai\"", "\"CHENNAI\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THEATRE_ALREADY_EXISTS"));
        // same name in another city is fine
        adminPost("/api/v1/admin/theatres", THEATRE.replace("Chennai", "Mumbai")).andExpect(status().isCreated());
    }

    @Test
    void duplicateScreenNameInTheatreIsRejected() throws Exception {
        long theatreId = theatre("T", "Chennai");
        String body = "{\"name\":\"Screen 1\",\"screenType\":\"STANDARD\"}";
        adminPost("/api/v1/admin/theatres/" + theatreId + "/screens", body).andExpect(status().isCreated());
        adminPost("/api/v1/admin/theatres/" + theatreId + "/screens", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREEN_NAME_TAKEN"));
        adminPost("/api/v1/admin/theatres/999999/screens", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEATRE_NOT_FOUND"));
    }

    @Test
    void invalidLayoutsAreRejected() throws Exception {
        long screenId = theatreService.addScreen(theatre("T", "Chennai"),
                new com.cinebot.screen.dto.ScreenRequest("S", com.cinebot.screen.ScreenType.STANDARD)).id();
        String url = "/api/v1/admin/screens/" + screenId + "/layout";

        adminPut(url, "{\"rows\":[{\"label\":\"A\",\"seats\":5,\"type\":\"REGULAR\"},"
                + "{\"label\":\"A\",\"seats\":5,\"type\":\"REGULAR\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Duplicate row label A"));
        adminPut(url, "{\"rows\":[{\"label\":\"a1\",\"seats\":5,\"type\":\"REGULAR\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        adminPut(url, "{\"rows\":[{\"label\":\"A\",\"seats\":0,\"type\":\"REGULAR\"}]}")
                .andExpect(status().isBadRequest());
        adminPut(url, "{\"rows\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void publicTheatreListIsByCityCaseInsensitiveAndHidesInactive() throws Exception {
        theatreService.create(new TheatreRequest("INOX Anna Nagar", "INOX", "Chennai", "x", null, null, true));
        theatre("PVR Velachery", "Chennai");
        theatre("PVR Lower Parel", "Mumbai");
        long closed = theatre("Old Cinema", "Chennai");
        theatreService.update(closed, new TheatreRequest("Old Cinema", null, "Chennai", "x", null, null, false));

        mvc.perform(get("/api/v1/theatres").param("city", "chennai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].name").value(contains("INOX Anna Nagar", "PVR Velachery")));
        mvc.perform(get("/api/v1/theatres").param("city", "Chennai").param("brand", "pvr"))
                .andExpect(jsonPath("$.content[*].name").value(contains("PVR Velachery")));
        mvc.perform(get("/api/v1/theatres/{id}", closed)).andExpect(status().isNotFound());
    }

    @Test
    void cityIsRequiredForTheatreList() throws Exception {
        mvc.perform(get("/api/v1/theatres"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("city"));
    }

    @Test
    void venueAdministrationRequiresAdmin() throws Exception {
        mvc.perform(post("/api/v1/admin/theatres").contentType(MediaType.APPLICATION_JSON).content(THEATRE))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/theatres").header("Authorization", bearer(Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(THEATRE))
                .andExpect(status().isForbidden());
    }

    private ResultActions adminPost(String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions adminPut(String url, String json) throws Exception {
        return mvc.perform(put(url).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long id(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
    }
}
