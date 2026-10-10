package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The price series.
 *
 * <p>What matters here is not that rows appear, but <em>which</em> changes count. The series
 * is going to be the training data for price estimation, so a draft being edited into shape
 * must not look like a seller dropping their price.
 */
class PriceHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingPriceHistoryRepository priceHistory;

    @Test
    @DisplayName("publishing records the opening price")
    void publishRecordsTheInitialPrice() throws Exception {
        JsonNode seller = register(uniqueEmail("price-initial"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        List<ListingPriceHistory> series = priceHistory.findSeries(UUID.fromString(id));
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().getReason()).isEqualTo(PriceChangeReason.INITIAL);
        assertThat(series.getFirst().getPriceAmount()).isEqualTo(1_899_000L);
        assertThat(series.getFirst().getPriceCurrency().getCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("editing a draft's price is not history")
    void draftEditsAreNotRecorded() throws Exception {
        JsonNode seller = register(uniqueEmail("price-draft"), UserRole.PARTICULIER);
        String id = createDraft(seller);

        // Three passes at the number before anyone could see it. This is composition, and
        // recording it would put price movements in the series that no buyer ever saw.
        patchPrice(seller, id, 1_750_000L);
        patchPrice(seller, id, 1_700_000L);
        patchPrice(seller, id, 1_690_000L);

        assertThat(priceHistory.findSeries(UUID.fromString(id))).isEmpty();
    }

    @Test
    @DisplayName("the opening price is the one it was published at, not the one it was drafted at")
    void initialPriceIsThePublishedOne() throws Exception {
        JsonNode seller = register(uniqueEmail("price-published"), UserRole.PARTICULIER);
        String id = createDraft(seller);
        patchPrice(seller, id, 1_650_000L);
        publish(seller, id);

        List<ListingPriceHistory> series = priceHistory.findSeries(UUID.fromString(id));
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().getPriceAmount()).isEqualTo(1_650_000L);
    }

    @Test
    @DisplayName("every price change after publication is appended, in order")
    void changesAfterPublicationAreAppended() throws Exception {
        JsonNode seller = register(uniqueEmail("price-drops"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        patchPrice(seller, id, 1_799_000L);
        patchPrice(seller, id, 1_699_000L);
        patchPrice(seller, id, 1_599_000L);

        List<ListingPriceHistory> series = priceHistory.findSeries(UUID.fromString(id));
        assertThat(series).extracting(ListingPriceHistory::getPriceAmount)
                .containsExactly(1_899_000L, 1_799_000L, 1_699_000L, 1_599_000L);
        assertThat(series).extracting(ListingPriceHistory::getReason)
                .containsExactly(PriceChangeReason.INITIAL, PriceChangeReason.SELLER_EDIT,
                        PriceChangeReason.SELLER_EDIT, PriceChangeReason.SELLER_EDIT);
    }

    @Test
    @DisplayName("an edit that does not move the price adds nothing")
    void unchangedPriceAddsNothing() throws Exception {
        JsonNode seller = register(uniqueEmail("price-same"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        // A title edit, and the price resubmitted unchanged — which is what a form that
        // sends every field does on every save.
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "BMW 320d xDrive M Sport — reduced",
                                        "priceAmount", 1_899_000L))))
                .andExpect(status().isOk());

        assertThat(priceHistory.findSeries(UUID.fromString(id))).hasSize(1);
    }

    @Test
    @DisplayName("changing currency alone is a price change")
    void currencyChangeIsRecorded() throws Exception {
        JsonNode seller = register(uniqueEmail("price-currency"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        // 1 899 000 minor units is a different asking price in a different currency, so the
        // amount staying the same does not make this a non-event.
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("currencyCode", "CHF"))))
                .andExpect(status().isOk());

        List<ListingPriceHistory> series = priceHistory.findSeries(UUID.fromString(id));
        assertThat(series).hasSize(2);
        assertThat(series.getLast().getPriceCurrency().getCode()).isEqualTo("CHF");
        assertThat(series.getLast().getPriceAmount()).isEqualTo(1_899_000L);
    }

    @Test
    @DisplayName("republishing does not record the price a second time")
    void republishingDoesNotDuplicateTheOpeningPrice() throws Exception {
        JsonNode seller = register(uniqueEmail("price-republish"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());

        // INITIAL means "the first price the market saw", and it already has.
        assertThat(priceHistory.findSeries(UUID.fromString(id))).hasSize(1);
    }

    @Test
    @DisplayName("a price change while paused is still recorded")
    void pausedEditsAreRecorded() throws Exception {
        JsonNode seller = register(uniqueEmail("price-paused"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);

        mockMvc.perform(post("/api/v1/listings/" + id + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        patchPrice(seller, id, 1_500_000L);

        // The listing has been public and will be again. Pausing is not drafting, and the
        // seller re-pricing while hidden is exactly the signal worth keeping.
        assertThat(priceHistory.findSeries(UUID.fromString(id)))
                .extracting(ListingPriceHistory::getPriceAmount)
                .containsExactly(1_899_000L, 1_500_000L);
    }

    @Test
    @DisplayName("the history is not exposed by any endpoint yet")
    void historyIsNotPublic() throws Exception {
        JsonNode seller = register(uniqueEmail("price-private"), UserRole.PARTICULIER);
        String id = createAndPublish(seller);
        patchPrice(seller, id, 1_750_000L);

        // Deliberate: it is an input to price estimation, not a feature. If it ever becomes
        // one, that is a product decision and this assertion is what will fail first.
        // 4xx rather than a specific code: there is no such route, so what answers is the
        // catch-all authentication rule, and which 4xx that is does not matter here.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/listings/" + id + "/price-history"))
                .andExpect(status().is4xxClientError());

        String detail = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/listings/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("priceHistory");
    }

    // --- helpers ----------------------------------------------------------

    private String createDraft(JsonNode session) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createAndPublish(JsonNode session) throws Exception {
        String id = createDraft(session);
        publish(session, id);
        return id;
    }

    private void publish(JsonNode session, String listingId) throws Exception {
        attachReadyPhoto(listingId);
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    private void patchPrice(JsonNode session, String listingId, long amount) throws Exception {
        mockMvc.perform(patch("/api/v1/listings/" + listingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priceAmount", amount))))
                .andExpect(status().isOk());
    }
}
