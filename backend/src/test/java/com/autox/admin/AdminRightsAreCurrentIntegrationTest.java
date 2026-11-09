package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Administrator rights end when the account's do, not when the token does.
 *
 * <p>The endpoints are read from the application itself rather than listed here, so an admin
 * endpoint added later is held to this without anyone remembering to add it.
 */
class AdminRightsAreCurrentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private AppUser admin;
    private JsonNode session;

    @BeforeEach
    void anAdministratorIsSignedIn() throws Exception {
        admin = createUser(uniqueEmail("admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        session = signIn(admin);
    }

    private JsonNode signIn(AppUser user) throws Exception {
        return json(postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", "correct-horse-battery")));
    }

    private record Endpoint(HttpMethod method, String path) {
    }

    /** Every admin endpoint the application serves, with an id nothing has. */
    private List<Endpoint> adminEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> {
            for (String pattern : mapping.getPatternValues()) {
                if (pattern.startsWith("/api/v1/admin")) {
                    for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                        endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()),
                                pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString())));
                    }
                }
            }
        });
        return endpoints;
    }

    private MvcResult call(Endpoint endpoint, JsonNode caller) throws Exception {
        MockHttpServletRequestBuilder builder = request(endpoint.method(), endpoint.path())
                .header(HttpHeaders.AUTHORIZATION, bearer(caller));
        if (endpoint.method() != HttpMethod.GET) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mockMvc.perform(builder).andReturn();
    }

    private void assertRefusedEverywhere(JsonNode caller) throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
            MvcResult result = call(endpoint, caller);
            assertThat(result.getResponse().getStatus()).as("%s %s", endpoint.method(), endpoint.path()).isEqualTo(403);
            assertThat(json(result).path("code").asText()).as(endpoint.path()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    @DisplayName("the check finds every admin endpoint: moderation, reports, the dashboard and users")
    void findsThemAll() {
        assertThat(adminEndpoints()).extracting(Endpoint::path).anySatisfy(path -> assertThat(path).startsWith("/api/v1/admin/listings"));
        assertThat(adminEndpoints()).extracting(Endpoint::path).anySatisfy(path -> assertThat(path).startsWith("/api/v1/admin/message"));
        assertThat(adminEndpoints()).extracting(Endpoint::path).contains("/api/v1/admin/stats", "/api/v1/admin/users");
        assertThat(adminEndpoints()).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("an administrator in good standing gets past the check on each of them")
    void standing() throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
            assertThat(call(endpoint, session).getResponse().getStatus())
                    .as("%s %s", endpoint.method(), endpoint.path())
                    .isNotIn(401, 403);
        }
    }

    @Test
    @DisplayName("a demoted administrator is refused at once on each of them")
    void demoted() throws Exception {
        for (String role : List.of("PARTICULIER", "PROFESSIONNEL")) {
            jdbcTemplate.update("UPDATE app_user SET role = ? WHERE id = ?", role, admin.getId());
            assertRefusedEverywhere(session);
        }
    }

    @Test
    @DisplayName("a suspended administrator is not signed in at all any more, on each of them")
    void suspended() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", admin.getId());
        assertUnauthenticatedEverywhere(session);
    }

    @Test
    @DisplayName("a closed account, and a token for an account that no longer exists, are not signed in at all")
    void gone() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET deleted_at = now() WHERE id = ?", admin.getId());
        assertUnauthenticatedEverywhere(session);

        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", admin.getId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", admin.getId());
        assertUnauthenticatedEverywhere(session);
    }

    /** No account behind the token: a 401, as for an expired one, rather than a 403 for a lesser role. */
    private void assertUnauthenticatedEverywhere(JsonNode caller) throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
            MvcResult result = call(endpoint, caller);
            assertThat(result.getResponse().getStatus()).as("%s %s", endpoint.method(), endpoint.path()).isEqualTo(401);
            assertThat(json(result).path("code").asText()).as(endpoint.path()).isEqualTo("INVALID_TOKEN");
        }
    }

    @Test
    @DisplayName("restored, the same token works again: the row decides, each time")
    void restored() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", admin.getId());
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isUnauthorized());

        jdbcTemplate.update("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?", admin.getId());
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token that never said ADMIN gets nothing from a promotion until the next one")
    void onlyTakenAway() throws Exception {
        AppUser person = createUser(uniqueEmail("person"), UserRole.PARTICULIER, UserStatus.ACTIVE);
        JsonNode token = signIn(person);
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", person.getId());

        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(signIn(person))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the sellers' endpoints stop treating them as an administrator too")
    void sellersEndpoints() throws Exception {
        // The same claim lets an administrator act on anyone's listing, outside the admin paths.
        JsonNode seller = register(uniqueEmail("seller"), UserRole.PROFESSIONNEL);
        String listing = json(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String edit = objectMapper.writeValueAsString(Map.of("title", "Modifiée par un administrateur"));

        mockMvc.perform(patch("/api/v1/listings/" + listing).header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER' WHERE id = ?", admin.getId());
        String stale = objectMapper.writeValueAsString(Map.of("title", "Modifiée après la rétrogradation"));

        int edited = mockMvc.perform(patch("/api/v1/listings/" + listing).header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andReturn().getResponse().getStatus();
        int deleted = mockMvc.perform(delete("/api/v1/listings/" + listing).header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andReturn().getResponse().getStatus();

        assertThat(edited).isIn(403, 404);
        assertThat(deleted).isIn(403, 404);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM listing WHERE id = ?::uuid", String.class, listing))
                .isEqualTo("Modifiée par un administrateur");
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM listing WHERE id = ?::uuid", Boolean.class, listing))
                .isTrue();
    }

    @Test
    @DisplayName("a demoted administrator keeps their own account, as anyone does")
    void keepsOwnAccount() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER' WHERE id = ?", admin.getId());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PARTICULIER"));
    }
}
