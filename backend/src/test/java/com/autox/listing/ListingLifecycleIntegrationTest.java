package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Status transitions, the active-listing cap, expiry and ownership. */
class ListingLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingRepository listings;
    @Autowired ListingProperties listingProperties;
    @Autowired ListingService listingService;

    // --- publish / unpublish ----------------------------------------------

    @Test
    @DisplayName("publishing makes a listing active and sets the expiry window")
    void publishGoesLiveAndSetsExpiry() throws Exception {
        JsonNode seller = register(uniqueEmail("publisher"), UserRole.PARTICULIER);
        String id = createListing(seller);

        String body = mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Instant expiresAt = Instant.parse(objectMapper.readTree(body).get("expiresAt").asText());
        long days = ChronoUnit.DAYS.between(Instant.now(), expiresAt);
        assertThat(days).isBetween((long) listingProperties.expiryDays() - 1, (long) listingProperties.expiryDays());
    }

    @Test
    @DisplayName("unpublishing pauses a live listing without losing it")
    void unpublishPauses() throws Exception {
        JsonNode seller = register(uniqueEmail("unpublisher"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                // The publication date is history and is not erased.
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a paused listing can be published again")
    void pausedCanBeRepublished() throws Exception {
        JsonNode seller = register(uniqueEmail("republisher"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("publishing without a photo is refused, with a code the frontend can act on")
    void publishRequiresAPhoto() throws Exception {
        JsonNode seller = register(uniqueEmail("photoless"), UserRole.PARTICULIER);
        String id = createListingWithoutPhoto(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));

        assertThat(listings.findActiveById(UUID.fromString(id)).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.DRAFT);

        // With a photo, the same call succeeds.
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("a pending upload does not count as a photo")
    void pendingUploadDoesNotSatisfyThePhotoRequirement() throws Exception {
        JsonNode seller = register(uniqueEmail("pending-only"), UserRole.PARTICULIER);
        String id = createListingWithoutPhoto(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "later.jpg", "contentType", "image/jpeg", "byteSize", 5000))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));
    }

    // --- invalid transitions ----------------------------------------------

    @Test
    @DisplayName("unpublishing a draft is refused, and the response names the legal moves")
    void cannotUnpublishADraft() throws Exception {
        JsonNode seller = register(uniqueEmail("bad-unpublish"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Cannot move a listing from DRAFT to PAUSED")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Allowed from DRAFT")));
    }

    @Test
    @DisplayName("publishing an already-active listing is refused")
    void cannotPublishTwice() throws Exception {
        JsonNode seller = register(uniqueEmail("double-publish"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Listing is already ACTIVE."));
    }

    @Test
    @DisplayName("renewing a draft is refused — there is nothing to extend")
    void cannotRenewADraft() throws Exception {
        JsonNode seller = register(uniqueEmail("bad-renew"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/renew")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Only an active or expired listing can be renewed")));
    }

    @Test
    @DisplayName("a removed listing cannot be edited")
    void removedListingIsNotEditable() throws Exception {
        JsonNode seller = register(uniqueEmail("edit-removed"), UserRole.PARTICULIER);
        String id = createListing(seller);
        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());

        // Soft-deleted, so it is invisible rather than merely locked.
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Too late"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the transition table itself rejects every illegal move")
    void transitionTableIsExhaustive() {
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.PAUSED)).isFalse();
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.SOLD)).isFalse();
        assertThat(ListingStatus.DRAFT.canTransitionTo(ListingStatus.EXPIRED)).isFalse();

        assertThat(ListingStatus.ACTIVE.canTransitionTo(ListingStatus.PAUSED)).isTrue();
        assertThat(ListingStatus.ACTIVE.canTransitionTo(ListingStatus.DRAFT)).isFalse();

        assertThat(ListingStatus.PAUSED.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
        assertThat(ListingStatus.PAUSED.canTransitionTo(ListingStatus.EXPIRED)).isFalse();

        assertThat(ListingStatus.EXPIRED.canTransitionTo(ListingStatus.ACTIVE)).isTrue();
        assertThat(ListingStatus.SOLD.canTransitionTo(ListingStatus.ACTIVE)).isFalse();
        assertThat(ListingStatus.SOLD.canTransitionTo(ListingStatus.REMOVED)).isTrue();

        // REMOVED is terminal until the moderation endpoints make reinstatement reachable.
        assertThat(ListingStatus.REMOVED.allowedTargets()).isEmpty();

        assertThat(ListingStatus.ACTIVE.canTransitionTo(null)).isFalse();
    }

    // --- expiry and renewal -----------------------------------------------

    @Test
    @DisplayName("the sweeper expires listings past their date, and renewing revives them")
    void expiryAndRenewal() throws Exception {
        JsonNode seller = register(uniqueEmail("expirer"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        // Back-date the expiry rather than waiting 60 days.
        jdbcTemplate.update("UPDATE listing SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), UUID.fromString(id));

        assertThat(listingService.expireDueListings()).isEqualTo(1);
        assertThat(listings.findActiveById(UUID.fromString(id)).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.EXPIRED);

        mockMvc.perform(post("/api/v1/listings/" + id + "/renew")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("renewing a live listing extends it without changing its state")
    void renewExtendsAnActiveListing() throws Exception {
        JsonNode seller = register(uniqueEmail("extender"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        Instant before = listings.findActiveById(UUID.fromString(id)).orElseThrow().getExpiresAt();
        jdbcTemplate.update("UPDATE listing SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(before.minus(10, ChronoUnit.DAYS)), UUID.fromString(id));

        mockMvc.perform(post("/api/v1/listings/" + id + "/renew")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Instant after = listings.findActiveById(UUID.fromString(id)).orElseThrow().getExpiresAt();
        assertThat(after).isAfter(before.minus(10, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("the sweeper leaves paused and draft listings alone")
    void sweeperOnlyTouchesActiveListings() throws Exception {
        JsonNode seller = register(uniqueEmail("sweeper-scope"), UserRole.PARTICULIER);
        String draft = createListing(seller);
        String paused = createListing(seller);
        publish(seller, paused);
        mockMvc.perform(post("/api/v1/listings/" + paused + "/unpublish")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE listing SET expires_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(listingService.expireDueListings()).isZero();
        assertThat(listings.findActiveById(UUID.fromString(draft)).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.DRAFT);
        assertThat(listings.findActiveById(UUID.fromString(paused)).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.PAUSED);
    }

    // --- sold --------------------------------------------------------------

    @Test
    @DisplayName("the seller can mark a live listing sold, and sold_at is recorded")
    void sellerCanMarkSold() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-seller"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.soldAt").isNotEmpty());

        assertThat(listings.findActiveById(UUID.fromString(id)).orElseThrow().getSoldAt()).isNotNull();
    }

    @Test
    @DisplayName("a paused listing can also be marked sold")
    void pausedCanBeMarkedSold() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-paused"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));
    }

    @Test
    @DisplayName("a draft cannot be sold")
    void draftCannotBeSold() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-draft"), UserRole.PARTICULIER);

        mockMvc.perform(post("/api/v1/listings/" + createListing(seller) + "/sold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Cannot move a listing from DRAFT to SOLD")));
    }

    @Test
    @DisplayName("a sold listing stays readable by its owner and frees a quota slot")
    void soldListingRemainsVisibleToItsOwner() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-visible"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"));

        // SOLD does not occupy a slot, so the cap is free again.
        assertThat(ListingStatus.SOLD.occupiesQuota()).isFalse();
        for (int i = 0; i < listingProperties.particulierActiveLimit(); i++) {
            publish(seller, createListing(seller));
        }
    }

    @Test
    @DisplayName("an admin cannot assert a sale on the seller's behalf")
    void adminCannotMarkSold() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-not-admin"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        createUser(uniqueEmail("sold-admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode admin = loginAsOnlyAdmin();

        mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Only the seller can mark a listing as sold."));
    }

    @Test
    @DisplayName("another seller cannot mark someone's listing sold")
    void strangerCannotMarkSold() throws Exception {
        JsonNode seller = register(uniqueEmail("sold-owner"), UserRole.PARTICULIER);
        JsonNode stranger = register(uniqueEmail("sold-stranger"), UserRole.PARTICULIER);
        String id = createListing(seller);
        publish(seller, id);

        mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    // --- active listing cap ------------------------------------------------

    @Test
    @DisplayName("a private individual cannot exceed the active-listing cap")
    void particulierCapIsEnforcedOnPublish() throws Exception {
        int limit = listingProperties.particulierActiveLimit();
        JsonNode seller = register(uniqueEmail("capped"), UserRole.PARTICULIER);

        for (int i = 0; i < limit; i++) {
            publish(seller, createListing(seller));
        }

        String oneTooMany = createListing(seller);
        mockMvc.perform(post("/api/v1/listings/" + oneTooMany + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_LISTING_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum of " + limit)));
    }

    @Test
    @DisplayName("pausing a listing frees a slot under the cap")
    void pausingFreesASlot() throws Exception {
        int limit = listingProperties.particulierActiveLimit();
        JsonNode seller = register(uniqueEmail("frees-slot"), UserRole.PARTICULIER);

        String first = createListing(seller);
        publish(seller, first);
        for (int i = 1; i < limit; i++) {
            publish(seller, createListing(seller));
        }

        String blocked = createListing(seller);
        mockMvc.perform(post("/api/v1/listings/" + blocked + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/listings/" + first + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/" + blocked + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("a dealer is not capped")
    void professionnelIsUncapped() throws Exception {
        JsonNode dealer = register(uniqueEmail("dealer-uncapped"), UserRole.PROFESSIONNEL);

        for (int i = 0; i < listingProperties.particulierActiveLimit() + 2; i++) {
            publish(dealer, createListing(dealer));
        }

        mockMvc.perform(get("/api/v1/listings/mine")
                        .param("status", "ACTIVE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(jsonPath("$.totalElements")
                        .value(listingProperties.particulierActiveLimit() + 2));
    }

    @Test
    @DisplayName("a per-account override beats the role default")
    void perAccountOverrideWins() throws Exception {
        String email = uniqueEmail("override");
        JsonNode seller = register(email, UserRole.PARTICULIER);

        AppUser user = users.findActiveByEmail(email).orElseThrow();
        user.setActiveListingLimit((short) 1);
        users.saveAndFlush(user);

        publish(seller, createListing(seller));

        mockMvc.perform(post("/api/v1/listings/" + createListing(seller) + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_LISTING_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("maximum of 1")));
    }

    @Test
    @DisplayName("soft-deleted listings do not consume a slot")
    void deletedListingsDoNotCountAgainstTheCap() throws Exception {
        int limit = listingProperties.particulierActiveLimit();
        JsonNode seller = register(uniqueEmail("deleted-slot"), UserRole.PARTICULIER);

        String doomed = createListing(seller);
        publish(seller, doomed);
        for (int i = 1; i < limit; i++) {
            publish(seller, createListing(seller));
        }

        mockMvc.perform(delete("/api/v1/listings/" + doomed).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/listings/" + createListing(seller) + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
    }

    // --- ownership ---------------------------------------------------------

    @Test
    @DisplayName("another seller cannot read, edit, publish or delete a listing that is not theirs")
    void otherSellersAreRefused() throws Exception {
        JsonNode owner = register(uniqueEmail("owner"), UserRole.PARTICULIER);
        JsonNode intruder = register(uniqueEmail("intruder"), UserRole.PARTICULIER);
        String id = createListing(owner);

        String auth = bearer(intruder);
        // 404 rather than 403 throughout: a 403 would confirm the id exists.
        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Mine now"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());

        // Untouched.
        assertThat(listings.findActiveById(UUID.fromString(id)).orElseThrow().getTitle())
                .isEqualTo("BMW 320d xDrive M Sport");
    }

    @Test
    @DisplayName("an admin can act on any listing")
    void adminCanActOnAnyListing() throws Exception {
        JsonNode owner = register(uniqueEmail("admin-subject"), UserRole.PARTICULIER);
        String id = createListing(owner);

        createUser(uniqueEmail("listing-admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode admin = loginAsOnlyAdmin();

        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the cap follows the seller, not the admin acting for them")
    void adminPublishingStillRespectsTheSellersCap() throws Exception {
        int limit = listingProperties.particulierActiveLimit();
        JsonNode seller = register(uniqueEmail("admin-capped"), UserRole.PARTICULIER);
        for (int i = 0; i < limit; i++) {
            publish(seller, createListing(seller));
        }
        String extra = createListing(seller);

        createUser(uniqueEmail("cap-admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode admin = loginAsOnlyAdmin();

        mockMvc.perform(post("/api/v1/listings/" + extra + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_LISTING_LIMIT_REACHED"));
    }

    // --- helpers -----------------------------------------------------------

    /**
     * A listing that is ready to publish. Publication requires a confirmed photo, and these
     * tests are about the lifecycle rather than about photos, so one is attached up front.
     */
    private String createListing(JsonNode session) throws Exception {
        String id = createListingWithoutPhoto(session);
        attachReadyPhoto(id);
        return id;
    }

    private String createListingWithoutPhoto(JsonNode session) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** Publication requires a confirmed photo, so one is attached first. */
    private void publish(JsonNode session, String listingId) throws Exception {
        attachReadyPhoto(listingId);
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    private JsonNode loginAsOnlyAdmin() throws Exception {
        String adminEmail = users.findAllActiveByRole(UserRole.ADMIN,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().getEmail();

        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", adminEmail, "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
