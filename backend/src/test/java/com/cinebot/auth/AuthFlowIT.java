package com.cinebot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AuthFlowIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    @Test
    void registerReturnsTokensAndNormalisesEmail() throws Exception {
        register("Alice@Example.COM", "Password123")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        String stored = jdbc.queryForObject("select password_hash from users", String.class);
        assertThat(stored).startsWith("$2").doesNotContain("Password123");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register("bob@example.com", "Password123").andExpect(status().isCreated());

        register("BOB@example.com", "Password456")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")));
    }

    @Test
    void malformedJsonReturnsProblem() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void loginSucceedsWithCorrectPasswordAndFailsUniformlyOtherwise() throws Exception {
        register("carol@example.com", "Password123").andExpect(status().isCreated());

        login("CAROL@example.com", "Password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        login("carol@example.com", "WrongPass1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("nobody@example.com", "Password123").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointRequiresValidAccessToken() throws Exception {
        String access = field(register("dave@example.com", "Password123"), "accessToken");

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dave@example.com"));

        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotReachAdminEndpoints() throws Exception {
        String access = field(register("erin@example.com", "Password123"), "accessToken");

        mvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refreshRotatesTokenAndOldTokenCannotBeUsedAgain() throws Exception {
        String r1 = field(register("frank@example.com", "Password123"), "refreshToken");

        String r2 = field(refresh(r1).andExpect(status().isOk()), "refreshToken");
        assertThat(r2).isNotEqualTo(r1);

        // Replaying r1 = reuse -> whole family revoked ...
        refresh(r1).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        // ... so even the legitimately rotated r2 no longer works.
        refresh(r2).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void reuseRevokesOnlyThatFamilyNotOtherDevices() throws Exception {
        String phone = field(register("gina@example.com", "Password123"), "refreshToken");
        String laptop = field(login("gina@example.com", "Password123"), "refreshToken");

        refresh(phone).andExpect(status().isOk());
        refresh(phone).andExpect(status().isUnauthorized()); // reuse on phone family

        refresh(laptop).andExpect(status().isOk());          // laptop session unaffected
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String r1 = field(register("hank@example.com", "Password123"), "refreshToken");

        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new java.util.HashMap<>(java.util.Map.of("refreshToken", r1)))))
                .andExpect(status().isNoContent());

        refresh(r1).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh("made-up-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshTokenIsStoredOnlyAsHash() throws Exception {
        String r1 = field(register("ivy@example.com", "Password123"), "refreshToken");

        Integer plain = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, r1);
        Integer hashed = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                RefreshTokenService.hash(r1));
        assertThat(plain).isZero();
        assertThat(hashed).isOne();
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "email", email, "password", password, "fullName", "Test User"))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token))));
    }

    private String field(ResultActions result, String name) throws Exception {
        JsonNode node = json.readTree(result.andReturn().getResponse().getContentAsString());
        return node.get(name).asText();
    }
}
