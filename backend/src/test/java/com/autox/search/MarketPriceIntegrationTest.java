package com.autox.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The price indicator against a real PostgreSQL: the rule in {@link MarketPrice}, where it is
 * written as SQL, is the thing under test.
 *
 * <p>Each test lays out a small market of Peugeot 208s — 2020, 40 000 km, 10 000 € unless
 * said otherwise — and one listing to judge against it.
 */
class MarketPriceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListingTestFixtures fixtures;

    private JsonNode seller;
    private ListingTestFixtures.Catalogue catalogue;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        seller = register(uniqueEmail("market"), UserRole.PROFESSIONNEL);
    }

    @Test
    @DisplayName("a price 10 % or more under the median of five comparables is a good deal")
    void goodDeal() throws Exception {
        market(5);
        String id = listing(Map.of("priceAmount", 880_000L));

        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(jsonPath("$.priceIndicator.level").value("GOOD_DEAL"))
                .andExpect(jsonPath("$.priceIndicator.medianPriceAmount").value(1_000_000))
                .andExpect(jsonPath("$.priceIndicator.comparableCount").value(5));
    }

    @Test
    @DisplayName("within 10 % either side is the market price, 10 % or more above is above it")
    void marketAndAbove() throws Exception {
        market(5);
        String market = listing(Map.of("priceAmount", 1_080_000L));

        mockMvc.perform(get("/api/v1/listings/" + market))
                .andExpect(jsonPath("$.priceIndicator.level").value("MARKET"));

        // The 10 800 € listing is now one of the comparables too: six of them, median
        // 10 000 €, and 11 500 € is 15 % over it.
        String above = listing(Map.of("priceAmount", 1_150_000L));
        mockMvc.perform(get("/api/v1/listings/" + above))
                .andExpect(jsonPath("$.priceIndicator.level").value("ABOVE_MARKET"))
                .andExpect(jsonPath("$.priceIndicator.comparableCount").value(6));
    }

    @Test
    @DisplayName("the verdict is on the search results too, the same as on the listing")
    void onTheResults() throws Exception {
        market(5);
        String id = listing(Map.of("priceAmount", 880_000L, "title", "The deal"));

        mockMvc.perform(get("/api/v1/listings").param("modelId", catalogue.twoOhEight().getId().toString()))
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].priceIndicator.level").value("GOOD_DEAL"));
    }

    @Test
    @DisplayName("with four comparables, nothing is said")
    void tooFewComparables() throws Exception {
        market(4);
        String id = listing(Map.of("priceAmount", 500_000L));

        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(jsonPath("$.priceIndicator").doesNotExist());
    }

    @Test
    @DisplayName("only the same model, two years either side, the same mileage band, used and live count")
    void whatIsComparable() throws Exception {
        market(4);
        // Each of these would be the fifth comparable if it qualified; none does.
        listing(Map.of("firstRegistrationYear", 2017));                // three years off
        listing(Map.of("mileageKm", 61_000));                          // the next band up
        listing(Map.of("condition", "NEW", "mileageKm", 10, "firstRegistrationYear", 2020));
        listing(Map.of("modelId", catalogue.threeSeries().getId().toString(),
                "makeId", catalogue.bmw().getId().toString()));        // another model
        String sold = listing(Map.of());
        act(sold, "sold");                                             // no longer live

        String id = listing(Map.of("priceAmount", 500_000L));
        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(jsonPath("$.priceIndicator").doesNotExist());

        // And the edges that do qualify: two years off, the top of the band.
        listing(Map.of("firstRegistrationYear", 2022, "mileageKm", 59_999));
        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(jsonPath("$.priceIndicator.comparableCount").value(5));
    }

    @Test
    @DisplayName("a new car is not judged, and nor is a vehicle typed in by hand")
    void notJudged() throws Exception {
        market(5);
        String brandNew = listing(Map.of("condition", "NEW", "mileageKm", 10, "priceAmount", 500_000L));

        Map<String, Object> typed = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        typed.put("priceAmount", 500_000L);
        String freeText = publish(create(typed));

        mockMvc.perform(get("/api/v1/listings/" + brandNew)).andExpect(jsonPath("$.priceIndicator").doesNotExist());
        mockMvc.perform(get("/api/v1/listings/" + freeText)).andExpect(jsonPath("$.priceIndicator").doesNotExist());
    }

    @Test
    @DisplayName("the good-deals filter returns the good deals, and the count agrees")
    void filter() throws Exception {
        market(5);
        String deal = listing(Map.of("priceAmount", 880_000L));

        mockMvc.perform(get("/api/v1/listings").param("priceLevel", "GOOD_DEAL"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(deal));
        mockMvc.perform(get("/api/v1/listings/count").param("priceLevel", "GOOD_DEAL"))
                .andExpect(jsonPath("$.count").value(1));
        // The market-priced ones are found by their own level, and the levels combine.
        mockMvc.perform(get("/api/v1/listings/count").param("priceLevel", "MARKET", "GOOD_DEAL"))
                .andExpect(jsonPath("$.count").value(6));
    }

    @Test
    @DisplayName("an unknown level is refused, not ignored")
    void unknownLevel() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("priceLevel", "BARGAIN"))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ------------------------------------------------------------------------

    /** {@code count} Peugeot 208s at the reference: 2020, 40 000 km, 10 000 €. */
    private void market(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            listing(Map.of());
        }
    }

    /** A published Peugeot 208 at the reference, with these fields changed. */
    private String listing(Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.remove("makeText");
        payload.remove("modelText");
        payload.put("makeId", catalogue.peugeot().getId().toString());
        payload.put("modelId", catalogue.twoOhEight().getId().toString());
        payload.put("title", "Peugeot 208");
        payload.put("firstRegistrationYear", 2020);
        payload.put("mileageKm", 40_000);
        payload.put("priceAmount", 1_000_000L);
        payload.putAll(overrides);
        return publish(create(payload));
    }

    private String create(Map<String, Object> payload) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String publish(String listingId) throws Exception {
        attachReadyPhoto(listingId);
        act(listingId, "publish");
        return listingId;
    }

    private void act(String listingId, String action) throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/" + action)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
    }
}
