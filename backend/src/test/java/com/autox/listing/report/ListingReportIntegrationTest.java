package com.autox.listing.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** "Signaler cette annonce", and the moderators' side of it. */
class ListingReportIntegrationTest extends AbstractIntegrationTest {

    private JsonNode seller;
    private JsonNode visitor;
    private String listing;

    @BeforeEach
    void setUp() throws Exception {
        seller = register(uniqueEmail("rep-seller"), UserRole.PROFESSIONNEL);
        visitor = register(uniqueEmail("rep-visitor"), UserRole.PARTICULIER);
        listing = publishedListing(seller);
    }

    @Test
    @DisplayName("a signed-in visitor reports a listing once, with a reason and an optional note")
    void reportOnce() throws Exception {
        report(visitor, listing, Map.of("reason", "SCAM", "note", "Demande un virement à l’étranger."))
                .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM listing_report");
        assertThat(row).containsEntry("reason", "SCAM").containsEntry("note", "Demande un virement à l’étranger.");
        assertThat(row.get("resolution")).isNull();
        reportStatus(visitor, listing).andExpect(jsonPath("$.reported").value(true));
        reportStatus(seller, listing).andExpect(jsonPath("$.reported").value(false));

        report(visitor, listing, Map.of("reason", "MISLEADING"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_ALREADY_REPORTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing_report", Integer.class)).isOne();
    }

    @Test
    @DisplayName("every reason the form offers is accepted; OTHER needs a note")
    void reasons() throws Exception {
        for (String reason : new String[] {"MISLEADING", "INAPPROPRIATE"}) {
            JsonNode someone = register(uniqueEmail("rep-" + reason.toLowerCase()), UserRole.PARTICULIER);
            report(someone, listing, Map.of("reason", reason)).andExpect(status().isNoContent());
        }
        JsonNode other = register(uniqueEmail("rep-other"), UserRole.PARTICULIER);
        report(other, listing, Map.of("reason", "OTHER")).andExpect(status().isBadRequest());
        report(other, listing, Map.of("reason", "OTHER", "note", "Photos d’une autre voiture."))
                .andExpect(status().isNoContent());
        report(other, listing, Map.of("reason", "NOT_A_REASON")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("not anonymously, not one's own listing, not without a verified address, not a listing nobody can see")
    void whoAndWhat() throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + listing + "/report").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isUnauthorized());
        report(seller, listing, Map.of("reason", "SCAM")).andExpect(status().isForbidden());

        JsonNode unverified = registerUnverified(uniqueEmail("rep-unverified"), UserRole.PARTICULIER);
        report(unverified, listing, Map.of("reason", "SCAM"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        jdbcTemplate.update("UPDATE listing SET status = 'REMOVED' WHERE id = ?::uuid", listing);
        report(visitor, listing, Map.of("reason", "SCAM")).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing_report", Integer.class)).isZero();
    }

    @Test
    @DisplayName("five reports an hour per account; the sixth answers 429")
    void rateLimited() throws Exception {
        for (int i = 0; i < ListingReportService.PER_WINDOW; i++) {
            report(visitor, publishedListing(register(uniqueEmail("rep-s" + i), UserRole.PROFESSIONNEL)),
                    Map.of("reason", "SCAM")).andExpect(status().isNoContent());
        }
        report(visitor, listing, Map.of("reason", "SCAM"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("moderators see open reports, oldest first, with the listing and how many reports it has")
    void queue() throws Exception {
        report(visitor, listing, Map.of("reason", "SCAM")).andExpect(status().isNoContent());
        report(register(uniqueEmail("rep-second"), UserRole.PARTICULIER), listing, Map.of("reason", "MISLEADING"))
                .andExpect(status().isNoContent());
        JsonNode admin = admin();

        mockMvc.perform(get("/api/v1/admin/listing-reports").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].reason").value("SCAM"))
                .andExpect(jsonPath("$.items[0].listingId").value(listing))
                .andExpect(jsonPath("$.items[0].openOnListing").value(2))
                .andExpect(jsonPath("$.items[0].reporter.displayName").exists());
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attention.openListingReports").value(2));

        mockMvc.perform(get("/api/v1/admin/listing-reports").header(HttpHeaders.AUTHORIZATION, bearer(visitor)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("dismissing closes every open report on the listing and leaves it online")
    void dismiss() throws Exception {
        report(visitor, listing, Map.of("reason", "SCAM")).andExpect(status().isNoContent());
        report(register(uniqueEmail("rep-second"), UserRole.PARTICULIER), listing, Map.of("reason", "SCAM"))
                .andExpect(status().isNoContent());
        JsonNode admin = admin();
        String report = firstReport();

        decide(admin, report, "dismiss").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForList("SELECT resolution FROM listing_report", String.class))
                .containsOnly("DISMISSED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM listing WHERE id = ?::uuid", String.class, listing))
                .isEqualTo("ACTIVE");
        decide(admin, report, "dismiss").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("removing takes the listing down as a moderation decision with the report's reason")
    void remove() throws Exception {
        report(visitor, listing, Map.of("reason", "SCAM")).andExpect(status().isNoContent());
        JsonNode admin = admin();

        decide(admin, firstReport(), "remove").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM listing WHERE id = ?::uuid", String.class, listing))
                .isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForMap("SELECT action, reason_code FROM moderation_action WHERE listing_id = ?::uuid",
                java.util.UUID.fromString(listing)))
                .containsEntry("action", "REMOVE").containsEntry("reason_code", "SUSPECTED_FRAUD");
        assertThat(jdbcTemplate.queryForObject("SELECT resolution FROM listing_report", String.class)).isEqualTo("REMOVED");
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.attention.openListingReports").value(0));
    }

    private ResultActions report(JsonNode who, String listingId, Map<String, String> body) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listingId + "/report").header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions reportStatus(JsonNode who, String listingId) throws Exception {
        return mockMvc.perform(get("/api/v1/listings/" + listingId + "/report").header(HttpHeaders.AUTHORIZATION, bearer(who)));
    }

    private ResultActions decide(JsonNode admin, String reportId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/listing-reports/" + reportId + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)));
    }

    private String firstReport() {
        return jdbcTemplate.queryForObject("SELECT id::text FROM listing_report ORDER BY created_at LIMIT 1", String.class);
    }

    private JsonNode admin() throws Exception {
        String email = uniqueEmail("rep-admin");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isOk()).andReturn());
    }

    private String publishedListing(JsonNode owner) throws Exception {
        String id = json(mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        return id;
    }
}
