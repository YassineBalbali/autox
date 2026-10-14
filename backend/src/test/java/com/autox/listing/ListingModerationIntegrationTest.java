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
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Moderation: the queue, the four decisions, the trail, and who may do any of it.
 *
 * <p>Three properties carry the weight here. Only an administrator can reach any of this.
 * A listing a moderator took down leaves the site at once and <em>stays</em> down — its
 * seller cannot put it back. And a moderator's removal is reversible where a seller's
 * delete is not.
 */
class ListingModerationIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_LISTINGS = "/api/v1/admin/listings";

    // --- who --------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  ''",
            "GET,  /{id}",
            "POST, /{id}/approve",
            "POST, /{id}/reject",
            "POST, /{id}/remove",
            "POST, /{id}/reinstate",
    })
    @DisplayName("every moderation endpoint is closed to anyone but an administrator")
    void onlyAdministrators(String method, String path) throws Exception {
        JsonNode seller = register(uniqueEmail("mod-seller"), UserRole.PARTICULIER);
        JsonNode dealer = register(uniqueEmail("mod-dealer"), UserRole.PROFESSIONNEL);
        String id = publishedListing(seller);
        String url = ADMIN_LISTINGS + path.replace("{id}", id);
        String body = objectMapper.writeValueAsString(Map.of("reasonCode", "DUPLICATE"));

        // No token: 401. A token with the wrong role: 403, the owner of the listing included.
        mockMvc.perform(request(method, url, body)).andExpect(status().isUnauthorized());
        mockMvc.perform(request(method, url, body).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(request(method, url, body).header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isForbidden());

        // And nothing happened.
        assertThat(statusOf(id)).isEqualTo(ListingStatus.ACTIVE);
        assertThat(moderationOf(id)).isEqualTo(ModerationStatus.NOT_REVIEWED);
        assertThat(trailLength(id)).isZero();
    }

    // --- the queue --------------------------------------------------------

    @Test
    @DisplayName("the queue is what is online and unreviewed, oldest first")
    void queueContents() throws Exception {
        JsonNode seller = register(uniqueEmail("queue"), UserRole.PROFESSIONNEL);
        String first = publishedListing(seller);
        String second = publishedListing(seller);
        String draft = draftListing(seller);
        String paused = publishedListing(seller);
        mockMvc.perform(post("/api/v1/listings/" + paused + "/unpublish")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
        String approved = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, approved, "approve", null).andExpect(status().isOk());

        JsonNode page = json(mockMvc.perform(get(ADMIN_LISTINGS).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(ids(page)).containsExactly(first, second);
        assertThat(ids(page)).doesNotContain(draft, paused, approved);
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a queued listing carries everything needed to judge it")
    void queueItemIsComplete() throws Exception {
        JsonNode seller = register(uniqueEmail("judged"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();

        mockMvc.perform(get(ADMIN_LISTINGS).param("view", "PENDING")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].listing.id").value(id))
                .andExpect(jsonPath("$.items[0].listing.title").value("BMW 320d xDrive M Sport"))
                .andExpect(jsonPath("$.items[0].listing.description").value("Well maintained, full service history."))
                .andExpect(jsonPath("$.items[0].listing.priceAmount").value(1899000))
                .andExpect(jsonPath("$.items[0].listing.vehicle.make").value("BMW"))
                .andExpect(jsonPath("$.items[0].listing.location.city").value("Paris"))
                .andExpect(jsonPath("$.items[0].listing.status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].listing.moderationStatus").value("NOT_REVIEWED"))
                // The photos, in the same answer.
                .andExpect(jsonPath("$.items[0].listing.images.length()").value(1))
                // Who is selling, including what a buyer is never shown.
                .andExpect(jsonPath("$.items[0].seller.email").value(seller.get("user").get("email").asText()))
                .andExpect(jsonPath("$.items[0].seller.role").value("PARTICULIER"))
                .andExpect(jsonPath("$.items[0].seller.memberSince").isNotEmpty())
                // What may be done with it, and that nothing has been yet.
                .andExpect(jsonPath("$.items[0].allowedActions.length()").value(3))
                .andExpect(jsonPath("$.items[0].trail.length()").value(0));
    }

    @Test
    @DisplayName("the three views partition what a moderator works on")
    void views() throws Exception {
        JsonNode seller = register(uniqueEmail("views"), UserRole.PROFESSIONNEL);
        String unreviewed = publishedListing(seller);
        String approved = publishedListing(seller);
        String rejected = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, approved, "approve", null).andExpect(status().isOk());
        decide(admin, rejected, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());

        assertThat(ids(view(admin, "PENDING"))).containsExactly(unreviewed);
        // Everything online, reviewed or not: a takedown can start from either.
        assertThat(ids(view(admin, "LIVE"))).containsExactlyInAnyOrder(unreviewed, approved);
        assertThat(ids(view(admin, "REMOVED"))).containsExactly(rejected);
    }

    @Test
    @DisplayName("a listing its seller deleted is in no view and cannot be opened")
    void sellerDeletedIsGone() throws Exception {
        JsonNode seller = register(uniqueEmail("deleter"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());
        JsonNode admin = admin();

        for (String name : List.of("PENDING", "LIVE", "REMOVED")) {
            assertThat(ids(view(admin, name))).isEmpty();
        }
        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
        // Permanent: this is the half of the split that cannot be undone.
        decide(admin, id, "reinstate", null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the list is paginated and its page size is capped")
    void pagination() throws Exception {
        JsonNode seller = register(uniqueEmail("pages"), UserRole.PROFESSIONNEL);
        for (int i = 0; i < 3; i++) {
            publishedListing(seller);
        }
        JsonNode admin = admin();

        mockMvc.perform(get(ADMIN_LISTINGS).param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(ADMIN_LISTINGS).param("size", "2").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get(ADMIN_LISTINGS).param("size", "51").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(ADMIN_LISTINGS).param("view", "EVERYTHING")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    // --- approve ----------------------------------------------------------

    @Test
    @DisplayName("approving keeps the listing online, records the verdict and leaves the queue")
    void approve() throws Exception {
        JsonNode seller = register(uniqueEmail("approved"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();

        decide(admin, id, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("ACTIVE"))
                .andExpect(jsonPath("$.listing.moderationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.allowedActions[0]").value("REMOVE"))
                .andExpect(jsonPath("$.trail.length()").value(1))
                .andExpect(jsonPath("$.trail[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.trail[0].moderatorId").value(admin.get("user").get("id").asText()))
                .andExpect(jsonPath("$.trail[0].moderatorName").value("Direct ADMIN"))
                .andExpect(jsonPath("$.trail[0].at").isNotEmpty())
                .andExpect(jsonPath("$.trail[0].reasonCode").doesNotExist());

        assertThat(ids(view(admin, "PENDING"))).isEmpty();
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("only a listing that is online and unreviewed can be approved")
    void approveRequiresAwaitingReview() throws Exception {
        JsonNode seller = register(uniqueEmail("not-approvable"), UserRole.PARTICULIER);
        String draft = draftListing(seller);
        String once = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, once, "approve", null).andExpect(status().isOk());

        decide(admin, draft, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // Twice is a conflict, not a second line in the trail.
        decide(admin, once, "approve", null).andExpect(status().isConflict());
        assertThat(trailLength(once)).isEqualTo(1);
        decide(admin, UUID.randomUUID().toString(), "approve", null).andExpect(status().isNotFound());
    }

    // --- reject -----------------------------------------------------------

    @Test
    @DisplayName("rejecting takes the listing off the site at once, with its reason on the trail")
    void reject() throws Exception {
        JsonNode seller = register(uniqueEmail("rejected"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isOk());

        decide(admin, id, "reject", Map.of("reasonCode", "MISLEADING_INFORMATION", "note", "  Mileage photo shows 187k.  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("REMOVED"))
                .andExpect(jsonPath("$.listing.moderationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.allowedActions[0]").value("REINSTATE"))
                .andExpect(jsonPath("$.trail[0].action").value("REJECT"))
                .andExpect(jsonPath("$.trail[0].reasonCode").value("MISLEADING_INFORMATION"))
                .andExpect(jsonPath("$.trail[0].note").value("Mileage photo shows 187k."));

        // Gone for a buyer: the page, and the search.
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        assertThat(ids(view(admin, "PENDING"))).isEmpty();
    }

    @Test
    @DisplayName("a refusal needs a reason, and 'other' needs a note")
    void rejectNeedsAReason() throws Exception {
        JsonNode seller = register(uniqueEmail("unexplained"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();

        decide(admin, id, "reject", Map.of("note", "no code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        decide(admin, id, "reject", Map.of("reasonCode", "BECAUSE_I_SAID_SO")).andExpect(status().isBadRequest());
        decide(admin, id, "reject", Map.of("reasonCode", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        decide(admin, id, "reject", Map.of("reasonCode", "OTHER", "note", "   ")).andExpect(status().isBadRequest());
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE", "note", "x".repeat(2001)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(ADMIN_LISTINGS + "/" + id + "/reject").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());

        // None of those refusals did anything.
        assertThat(statusOf(id)).isEqualTo(ListingStatus.ACTIVE);
        assertThat(trailLength(id)).isZero();

        decide(admin, id, "reject", Map.of("reasonCode", "OTHER", "note", "Seller asked us to."))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("only a listing awaiting review can be rejected")
    void rejectRequiresAwaitingReview() throws Exception {
        JsonNode seller = register(uniqueEmail("not-rejectable"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());

        // Already accepted: that is a takedown, which is a different line in the trail.
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(ListingStatus.ACTIVE);
    }

    // --- takedown ---------------------------------------------------------

    @Test
    @DisplayName("an approved listing can be taken down, with a reason")
    void takedown() throws Exception {
        JsonNode seller = register(uniqueEmail("taken-down"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());

        decide(admin, id, "remove", Map.of()).andExpect(status().isBadRequest());
        decide(admin, id, "remove", Map.of("reasonCode", "SUSPECTED_FRAUD", "note", "Reported by three buyers."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("REMOVED"))
                .andExpect(jsonPath("$.listing.moderationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.trail.length()").value(2))
                .andExpect(jsonPath("$.trail[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.trail[1].action").value("REMOVE"))
                .andExpect(jsonPath("$.trail[1].reasonCode").value("SUSPECTED_FRAUD"));

        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only a listing that is online can be taken down")
    void takedownRequiresOnline() throws Exception {
        JsonNode seller = register(uniqueEmail("not-online"), UserRole.PARTICULIER);
        String draft = draftListing(seller);
        String paused = publishedListing(seller);
        mockMvc.perform(post("/api/v1/listings/" + paused + "/unpublish")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
        JsonNode admin = admin();
        Map<String, Object> reason = Map.of("reasonCode", "DUPLICATE");

        decide(admin, draft, "remove", reason).andExpect(status().isConflict());
        decide(admin, paused, "remove", reason).andExpect(status().isConflict());
        assertThat(statusOf(draft)).isEqualTo(ListingStatus.DRAFT);
        assertThat(statusOf(paused)).isEqualTo(ListingStatus.PAUSED);
    }

    // --- what the seller is left with ---------------------------------------

    @Test
    @DisplayName("the seller sees a removed listing and why, and cannot put it back")
    void sellerCannotUndoAModerator() throws Exception {
        JsonNode seller = register(uniqueEmail("overruled"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "INAPPROPRIATE_PHOTOS", "note", "internal detail"))
                .andExpect(status().isOk());

        String mine = mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("REMOVED"))
                .andExpect(jsonPath("$.items[0].removalReason").value("INAPPROPRIATE_PHOTOS"))
                // The table now has moves out of REMOVED. None of them is the seller's.
                .andExpect(jsonPath("$.items[0].allowedTransitions.length()").value(0))
                .andExpect(jsonPath("$.items[0].renewable").value(false))
                .andExpect(jsonPath("$.items[0].editable").value(false))
                .andReturn().getResponse().getContentAsString();
        // The reason, and only the reason: not who decided, not what they noted.
        assertThat(mine).doesNotContain("internal detail").doesNotContain("moderator");

        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedTransitions.length()").value(0));

        for (String action : List.of("publish", "renew", "unpublish", "sold")) {
            mockMvc.perform(post("/api/v1/listings/" + id + "/" + action)
                            .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                    .andExpect(status().isConflict());
        }
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Fixed, honest"))))
                .andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(ListingStatus.REMOVED);
    }

    @Test
    @DisplayName("an administrator cannot reinstate through the seller's endpoints either")
    void noBackDoorAroundTheTrail() throws Exception {
        // POST /listings/{id}/publish accepts an administrator acting on any listing. If it
        // moved a removed listing back online, that would be a reinstatement with no line
        // in the trail.
        JsonNode seller = register(uniqueEmail("back-door"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(ListingStatus.REMOVED);
        assertThat(trailLength(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a removed listing no longer counts against its seller's quota")
    void removalFreesTheQuota() throws Exception {
        JsonNode seller = register(uniqueEmail("freed"), UserRole.PARTICULIER);
        String first = publishedListing(seller);
        publishedListing(seller);
        mockMvc.perform(get("/api/v1/listings/mine/quota").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.canPublish").value(false));

        decide(admin(), first, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/listings/mine/quota").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.canPublish").value(true));
    }

    @Test
    @DisplayName("the seller may delete a removed listing for good, after which it cannot be reinstated")
    void sellerDeleteAfterRemovalIsFinal() throws Exception {
        JsonNode seller = register(uniqueEmail("gave-up"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());

        decide(admin, id, "reinstate", null).andExpect(status().isNotFound());
        assertThat(ids(view(admin, "REMOVED"))).isEmpty();
        // The trail outlives the listing's visibility: the row and its history are kept.
        assertThat(trailLength(id)).isEqualTo(1);
    }

    // --- reinstate --------------------------------------------------------

    @Test
    @DisplayName("reinstating puts the listing back online, approved, with the whole story on the trail")
    void reinstate() throws Exception {
        JsonNode seller = register(uniqueEmail("reinstated"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "SUSPECTED_FRAUD")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isNotFound());

        decide(admin, id, "reinstate", Map.of("note", "Seller sent the registration document."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("ACTIVE"))
                // Somebody has now looked and decided it may be here: that is approval.
                .andExpect(jsonPath("$.listing.moderationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.allowedActions[0]").value("REMOVE"))
                .andExpect(jsonPath("$.trail.length()").value(2))
                .andExpect(jsonPath("$.trail[0].action").value("REJECT"))
                .andExpect(jsonPath("$.trail[1].action").value("REINSTATE"))
                .andExpect(jsonPath("$.trail[1].note").value("Seller sent the registration document."));

        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isOk());
        // It does not come back into the queue to be reviewed a second time.
        assertThat(ids(view(admin, "PENDING"))).isEmpty();
        // And it is the seller's again, with nothing left of the removal on it.
        mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].removalReason").doesNotExist());
    }

    @Test
    @DisplayName("reinstating works without a body")
    void reinstateWithoutABody() throws Exception {
        JsonNode seller = register(uniqueEmail("no-body"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());

        mockMvc.perform(post(ADMIN_LISTINGS + "/" + id + "/reinstate").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trail[1].note").doesNotExist());
    }

    @Test
    @DisplayName("a listing reinstated while its seller is at the cap comes back paused, not over the cap")
    void reinstateAtTheCap() throws Exception {
        // The test profile caps a private seller at two.
        JsonNode seller = register(uniqueEmail("capped"), UserRole.PARTICULIER);
        String removed = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, removed, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());
        publishedListing(seller);
        publishedListing(seller);

        decide(admin, removed, "reinstate", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("PAUSED"))
                .andExpect(jsonPath("$.listing.moderationStatus").value("APPROVED"));

        mockMvc.perform(get("/api/v1/listings/mine/quota").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.active").value(2));
        // Paused, it is the seller's to put online when there is room — and not before.
        mockMvc.perform(post("/api/v1/listings/" + removed + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_LISTING_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("a listing whose window ran out while it was down comes back expired")
    void reinstateAfterExpiry() throws Exception {
        JsonNode seller = register(uniqueEmail("lapsed"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE listing SET expires_at = now() - interval '1 day' WHERE id = ?::uuid", id);

        decide(admin, id, "reinstate", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.status").value("EXPIRED"));

        // Reinstating is not renewing. The seller renews, as for any expired listing.
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/listings/" + id + "/renew").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("only a removed listing can be reinstated")
    void reinstateRequiresRemoved() throws Exception {
        JsonNode seller = register(uniqueEmail("never-removed"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();

        decide(admin, id, "reinstate", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(trailLength(id)).isZero();
    }

    // --- the trail --------------------------------------------------------

    @Test
    @DisplayName("the trail says who did what, when and why, in order, and names each moderator")
    void trail() throws Exception {
        JsonNode seller = register(uniqueEmail("history"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode first = admin();
        JsonNode second = admin();

        decide(first, id, "approve", Map.of("note", "Looks fine.")).andExpect(status().isOk());
        decide(second, id, "remove", Map.of("reasonCode", "CONTACT_DETAILS_IN_TEXT")).andExpect(status().isOk());
        decide(first, id, "reinstate", null).andExpect(status().isOk());

        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trail.length()").value(3))
                .andExpect(jsonPath("$.trail[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.trail[0].note").value("Looks fine."))
                .andExpect(jsonPath("$.trail[0].moderatorId").value(first.get("user").get("id").asText()))
                .andExpect(jsonPath("$.trail[1].action").value("REMOVE"))
                .andExpect(jsonPath("$.trail[1].reasonCode").value("CONTACT_DETAILS_IN_TEXT"))
                .andExpect(jsonPath("$.trail[1].moderatorId").value(second.get("user").get("id").asText()))
                .andExpect(jsonPath("$.trail[2].action").value("REINSTATE"))
                .andExpect(jsonPath("$.trail[2].moderatorId").value(first.get("user").get("id").asText()));

        // The same trail travels with the listing in a list.
        mockMvc.perform(get(ADMIN_LISTINGS).param("view", "LIVE").header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(jsonPath("$.items[0].trail.length()").value(3));
    }

    @Test
    @DisplayName("a reason code this build no longer knows does not break reading the trail")
    void retiredReasonCode() throws Exception {
        JsonNode seller = register(uniqueEmail("old-code"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "DUPLICATE")).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE moderation_action SET reason_code = 'RETIRED_LONG_AGO' WHERE listing_id = ?::uuid", id);

        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trail[0].action").value("REJECT"))
                .andExpect(jsonPath("$.trail[0].reasonCode").doesNotExist());
        mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].removalReason").doesNotExist());
    }

    // --- an approval is of the listing as it was ----------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "title       | {\"title\": \"Something else entirely\"}",
            "description | {\"description\": \"Western Union only, write to me at ...\"}",
            "mileage     | {\"mileageKm\": 12000}",
            "vehicle     | {\"makeText\": \"Ferrari\", \"modelText\": \"F40\"}",
            "location    | {\"postalCode\": \"13001\", \"city\": \"Marseille\"}",
            "cleared     | {\"description\": \"\"}",
    })
    @DisplayName("editing the content of an approved listing sends it back to the queue, still online")
    void contentEditWithdrawsApproval(String what, String patch) throws Exception {
        JsonNode seller = register(uniqueEmail("bait-and-switch"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());
        assertThat(ids(view(admin, "PENDING"))).isEmpty();

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isOk())
                // It is not taken down: every unreviewed listing is online.
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.moderationStatus").value("NOT_REVIEWED"));

        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isOk());
        assertThat(ids(view(admin, "PENDING"))).containsExactly(id);

        // The moderator is told why it is back, and can decide again.
        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.editedSinceReview").value(true))
                .andExpect(jsonPath("$.trail.length()").value(1))
                .andExpect(jsonPath("$.allowedActions.length()").value(3));
        decide(admin, id, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listing.moderationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.editedSinceReview").value(false))
                .andExpect(jsonPath("$.trail.length()").value(2));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "price       | {\"priceAmount\": 1799000}",
            "negotiable  | {\"priceNegotiable\": false}",
            "vat         | {\"vatDeductible\": true}",
            "privacy     | {\"hideExactLocation\": false}",
    })
    @DisplayName("a change of price alone leaves the approval standing")
    void priceEditKeepsApproval(String what, String patch) throws Exception {
        JsonNode seller = register(uniqueEmail("price-drop"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));

        assertThat(ids(view(admin, "PENDING"))).isEmpty();
    }

    @Test
    @DisplayName("saving a form unchanged is not an edit")
    void resendingTheSameValuesKeepsApproval() throws Exception {
        // The seller's form sends every field of a step on each save. A listing that went
        // back to the queue whenever its seller pressed "save" would never leave it.
        JsonNode seller = register(uniqueEmail("no-op"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());

        Map<String, Object> same = new java.util.HashMap<>(ListingTestFixtures.freeTextListingPayload());
        same.put("title", "  BMW 320d xDrive M Sport  ");
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(same)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("price and content changed together still withdraw the approval")
    void priceWithContentWithdrawsApproval() throws Exception {
        JsonNode seller = register(uniqueEmail("both"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        decide(admin(), id, "approve", null).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceAmount\": 100, \"title\": \"Now something else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("NOT_REVIEWED"));
    }

    @Test
    @DisplayName("adding or removing a photo withdraws the approval; reordering them does not")
    void photoChangesWithdrawApproval() throws Exception {
        JsonNode seller = register(uniqueEmail("photo-swap"), UserRole.PARTICULIER);
        String id = draftListing(seller);
        String first = uploadPhoto(seller, id);
        String second = uploadPhoto(seller, id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        JsonNode admin = admin();
        decide(admin, id, "approve", null).andExpect(status().isOk());

        // The same photos in another order: all of them were seen.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/listings/" + id + "/images/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("imageIds", List.of(second, first)))))
                .andExpect(status().isOk());
        assertThat(moderationOf(id)).isEqualTo(ModerationStatus.APPROVED);

        // A photo nobody has seen.
        uploadPhoto(seller, id);
        assertThat(moderationOf(id)).isEqualTo(ModerationStatus.NOT_REVIEWED);
        assertThat(statusOf(id)).isEqualTo(ListingStatus.ACTIVE);

        // And one taken away from what was approved.
        decide(admin, id, "approve", null).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/listings/" + id + "/images/" + first)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());
        assertThat(moderationOf(id)).isEqualTo(ModerationStatus.NOT_REVIEWED);
    }

    @Test
    @DisplayName("a requested upload that is never completed changes nothing")
    void unconfirmedUploadKeepsApproval() throws Exception {
        JsonNode seller = register(uniqueEmail("half-upload"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        decide(admin(), id, "approve", null).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/" + id + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("filename", "a.jpg", "contentType", "image/jpeg", "byteSize", 1000))))
                .andExpect(status().isCreated());

        assertThat(moderationOf(id)).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    @DisplayName("editing a listing nobody has reviewed leaves it where it is, without the 'edited' mark")
    void editingAnUnreviewedListing() throws Exception {
        JsonNode seller = register(uniqueEmail("still-queued"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Retitled before anyone looked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("NOT_REVIEWED"));

        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.editedSinceReview").value(false));
    }

    @Test
    @DisplayName("a reinstated listing is covered too: editing it sends it back to the queue")
    void editAfterReinstatement() throws Exception {
        JsonNode seller = register(uniqueEmail("second-chance"), UserRole.PARTICULIER);
        String id = publishedListing(seller);
        JsonNode admin = admin();
        decide(admin, id, "reject", Map.of("reasonCode", "MISLEADING_INFORMATION")).andExpect(status().isOk());
        decide(admin, id, "reinstate", null).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Back to what it said before.\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(ADMIN_LISTINGS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.listing.moderationStatus").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.editedSinceReview").value(true));
    }

    // --- the transition table ---------------------------------------------

    @Test
    @DisplayName("the transition table describes what the API does: out of REMOVED, by a moderator only")
    void transitionTable() {
        assertThat(ListingStatus.REMOVED.allowedTargets())
                .containsExactlyInAnyOrder(ListingStatus.ACTIVE, ListingStatus.PAUSED, ListingStatus.EXPIRED);
        assertThat(ListingStatus.REMOVED.sellerTargets()).isEmpty();
        assertThat(ListingStatus.REMOVED.canSellerTransitionTo(ListingStatus.ACTIVE)).isFalse();

        // Everywhere else a seller's moves are the table's.
        for (ListingStatus status : ListingStatus.values()) {
            if (status != ListingStatus.REMOVED) {
                assertThat(status.sellerTargets()).isEqualTo(status.allowedTargets());
            }
        }

        // PENDING_REVIEW is reserved: nothing leads to it, and nothing leaves it.
        assertThat(ListingStatus.PENDING_REVIEW.allowedTargets()).isEmpty();
        for (ListingStatus status : ListingStatus.values()) {
            assertThat(status.canTransitionTo(ListingStatus.PENDING_REVIEW)).isFalse();
        }
    }

    // --- helpers ----------------------------------------------------------

    /** A fresh administrator, signed in. */
    private JsonNode admin() throws Exception {
        String email = uniqueEmail("moderator");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));
    }

    private String draftListing(JsonNode seller) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String publishedListing(JsonNode seller) throws Exception {
        String id = draftListing(seller);
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        return id;
    }

    /** A real photo, through the real flow: a slot, the bytes to storage, the confirmation. */
    private String uploadPhoto(JsonNode seller, String listingId) throws Exception {
        java.awt.image.BufferedImage picture =
                new java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "jpg", encoded);
        byte[] content = encoded.toByteArray();

        JsonNode ticket = json(mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "photo.jpg", "contentType", "image/jpeg", "byteSize", content.length))))
                .andExpect(status().isCreated())
                .andReturn());

        java.net.http.HttpRequest.Builder put = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(ticket.get("uploadUrl").asText()))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content));
        ticket.get("requiredHeaders").fields().forEachRemaining(header -> {
            // The JDK client computes Content-Length itself and refuses to be told it.
            if (!"Content-Length".equalsIgnoreCase(header.getKey())) {
                put.header(header.getKey(), header.getValue().asText());
            }
        });
        assertThat(java.net.http.HttpClient.newHttpClient()
                .send(put.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

        String imageId = ticket.get("imageId").asText();
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/images/" + imageId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        return imageId;
    }

    private ResultActions decide(JsonNode admin, String id, String action, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder call = post(ADMIN_LISTINGS + "/" + id + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin));
        if (body != null) {
            call.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(call);
    }

    private JsonNode view(JsonNode admin, String name) throws Exception {
        return json(mockMvc.perform(get(ADMIN_LISTINGS).param("view", name)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("listing").get("id").asText()));
        return ids;
    }

    private MockHttpServletRequestBuilder request(String method, String url, String body) {
        return "GET".equals(method)
                ? get(url)
                : post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ListingStatus statusOf(String id) {
        return ListingStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM listing WHERE id = ?::uuid", String.class, id));
    }

    private ModerationStatus moderationOf(String id) {
        return ModerationStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM listing WHERE id = ?::uuid", String.class, id));
    }

    private int trailLength(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM moderation_action WHERE listing_id = ?::uuid", Integer.class, id);
        return count == null ? 0 : count;
    }
}
