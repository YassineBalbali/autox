package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The active-listing cap under concurrent publication.
 *
 * <p>The only concurrency test in the suite, and it exists because the bug it covers was
 * found by reading rather than by testing: counting a seller's active listings and then
 * publishing one more is two statements, and at READ COMMITTED two requests can both read
 * the same count and both pass. Everything else here is sequential, which is exactly why
 * this went unnoticed.
 *
 * <p>The cap is the only monetisable limit in the product, so "roughly right" is not good
 * enough for it.
 */
class QuotaConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingRepository listings;

    @Test
    @DisplayName("two simultaneous publications cannot both take the last quota slot")
    void concurrentPublishRespectsTheCap() throws Exception {
        JsonNode seller = register(uniqueEmail("quota-race"), UserRole.PARTICULIER);
        String sellerId = seller.get("user").get("id").asText();

        // A cap of one makes the race a single contested slot, which is the smallest case
        // that can go wrong and the easiest to read when it does.
        jdbcTemplate.update("UPDATE app_user SET active_listing_limit = 1 WHERE id = ?::uuid", sellerId);

        String first = createDraftWithPhoto(seller);
        String second = createDraftWithPhoto(seller);

        List<Integer> statuses = publishSimultaneously(seller, List.of(first, second));

        assertThat(statuses).as("one publication succeeds and one is refused")
                .containsExactlyInAnyOrder(200, 409);
        assertThat(listings.countActiveBySeller(java.util.UUID.fromString(sellerId)))
                .as("the cap of one is not exceeded")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("four simultaneous publications fill the quota exactly, no more")
    void concurrentPublishFillsTheQuotaExactly() throws Exception {
        JsonNode seller = register(uniqueEmail("quota-race-many"), UserRole.PARTICULIER);
        String sellerId = seller.get("user").get("id").asText();
        jdbcTemplate.update("UPDATE app_user SET active_listing_limit = 2 WHERE id = ?::uuid", sellerId);

        List<String> drafts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            drafts.add(createDraftWithPhoto(seller));
        }

        List<Integer> statuses = publishSimultaneously(seller, drafts);

        assertThat(statuses).filteredOn(status -> status == 200).hasSize(2);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(2);
        assertThat(listings.countActiveBySeller(java.util.UUID.fromString(sellerId))).isEqualTo(2);
    }

    /**
     * Fires every publication from its own thread, released together, so they contend for
     * the seller lock rather than queueing politely behind one another.
     */
    private List<Integer> publishSimultaneously(JsonNode session, List<String> listingIds) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(listingIds.size());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String listingId : listingIds) {
                results.add(threads.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/v1/listings/" + listingId + "/publish")
                                    .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                            .andReturn().getResponse().getStatus();
                }));
            }

            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            threads.shutdownNow();
        }
    }

    private String createDraftWithPhoto(JsonNode session) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).get("id").asText();
        attachReadyPhoto(id);
        return id;
    }
}
