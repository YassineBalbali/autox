package com.autox.privacy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.storage.ObjectStorage;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** What the privacy tests share: accounts, listings with stored photos, conversations. */
abstract class PrivacyTestSupport extends AbstractIntegrationTest {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired ObjectStorage storage;

    String userId(JsonNode auth) {
        return auth.at("/user/id").asText();
    }

    JsonNode login(String email) throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn());
    }

    JsonNode admin() throws Exception {
        String email = uniqueEmail("privacy-admin");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return login(email);
    }

    /** A published listing, with a photo whose original and one variant really are in storage. */
    String publishedListing(JsonNode owner) throws Exception {
        String id = json(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        attachReadyPhoto(id);
        UUID imageId = jdbcTemplate.queryForObject(
                "SELECT id FROM listing_image WHERE listing_id = ?::uuid", UUID.class, id);
        String variantKey = "listings/" + id + "/" + imageId + "/card.jpg";
        jdbcTemplate.update("""
                INSERT INTO listing_image_variant (id, image_id, variant, storage_key, width, height, byte_size, created_at)
                VALUES (?, ?, 'CARD', ?, 800, 600, 3, now())""", UUID.randomUUID(), imageId, variantKey);
        for (String key : storedKeys(id)) {
            storage.write(key, new byte[] {1, 2, 3}, "image/jpeg");
        }
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    /** The listing's photo objects: originals and variants. */
    List<String> storedKeys(String listingId) {
        return jdbcTemplate.queryForList("""
                SELECT storage_key FROM listing_image WHERE listing_id = ?::uuid
                UNION ALL
                SELECT v.storage_key FROM listing_image_variant v JOIN listing_image i ON i.id = v.image_id
                 WHERE i.listing_id = ?::uuid""", String.class, listingId, listingId);
    }

    /** Starts a conversation about a listing; returns its id. */
    String contact(JsonNode buyer, String listingId, String body) throws Exception {
        return json(mockMvc.perform(post("/api/v1/listings/" + listingId + "/conversation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isOk())
                .andReturn()).get("id").asText();
    }

    void reply(JsonNode who, String conversationId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isOk());
    }

    JsonNode conversation(JsonNode who, String conversationId) throws Exception {
        return json(mockMvc.perform(get("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(who)))
                .andExpect(status().isOk())
                .andReturn());
    }

    void saveSearch(JsonNode who) throws Exception {
        // Alerts are e-mailed, so they want an address actually verified.
        jdbcTemplate.update("UPDATE app_user SET email_verified_at = now() WHERE id = ?::uuid", userId(who));
        mockMvc.perform(post("/api/v1/saved-searches").header(HttpHeaders.AUTHORIZATION, bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Mon alerte",
                                "criteria", Map.of(), "params", Map.of()))))
                .andExpect(status().is2xxSuccessful());
    }

    ResultActions deleteAccount(JsonNode who, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/me/deletion").header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    boolean stored(String key) {
        return storage.stat(key).isPresent();
    }
}
