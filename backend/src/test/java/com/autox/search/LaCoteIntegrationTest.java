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
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * La Cote against a real PostgreSQL: the price indicator's comparables, run for a car that is
 * described rather than listed.
 *
 * <p>The market is Peugeot 208s — petrol, 100 ch, 2020, 40 000 km, 10 000 € unless said
 * otherwise — and the car valued is one of those, unless said otherwise.
 */
class LaCoteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListingTestFixtures fixtures;

    private JsonNode seller;
    private ListingTestFixtures.Catalogue catalogue;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        seller = register(uniqueEmail("cote"), UserRole.PROFESSIONNEL);
    }

    @Test
    @DisplayName("five comparables give the median and the middle half around it, and what they rest on")
    void estimate() throws Exception {
        for (long price : new long[] {900_000L, 950_000L, 1_000_000L, 1_050_000L, 1_100_000L}) {
            listing(Map.of("priceAmount", price));
        }

        value(Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("EXACT"))
                .andExpect(jsonPath("$.comparableCount").value(5))
                .andExpect(jsonPath("$.minimumComparables").value(5))
                .andExpect(jsonPath("$.estimate.medianAmount").value(1_000_000))
                .andExpect(jsonPath("$.estimate.lowAmount").value(950_000))
                .andExpect(jsonPath("$.estimate.highAmount").value(1_050_000))
                .andExpect(jsonPath("$.estimate.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.basis.yearFrom").value(2020))
                .andExpect(jsonPath("$.basis.yearTo").value(2020))
                .andExpect(jsonPath("$.basis.mileageFromKm").value(40_000))
                .andExpect(jsonPath("$.basis.mileageToKm").value(40_000))
                // What was searched for: the indicator's window around this car.
                .andExpect(jsonPath("$.window.yearFrom").value(2018))
                .andExpect(jsonPath("$.window.yearTo").value(2022))
                .andExpect(jsonPath("$.window.mileageFromKm").value(30_000))
                .andExpect(jsonPath("$.window.mileageToKm").value(60_000))
                .andExpect(jsonPath("$.window.fuelType").value("PETROL"))
                .andExpect(jsonPath("$.window.powerFromHp").value(85))
                .andExpect(jsonPath("$.window.powerToHp").value(115))
                .andExpect(jsonPath("$.widerScopes.length()").value(0));
    }

    @Test
    @DisplayName("prices are brought to the car's age and mileage, then rounded to the hundred")
    void adjustedAndRounded() throws Exception {
        // Five 2019s at 10 000 €: at the age of a 2020 car each is worth 10 000 / 0.9 = 11 111 €.
        for (int i = 0; i < 5; i++) {
            listing(Map.of("firstRegistrationYear", 2019));
        }

        value(Map.of())
                .andExpect(jsonPath("$.estimate.medianAmount").value(1_110_000))
                .andExpect(jsonPath("$.basis.yearFrom").value(2019));
    }

    @Test
    @DisplayName("gives the same median as the price indicator does for a listing of that car")
    void agreesWithTheIndicator() throws Exception {
        for (long price : new long[] {930_000L, 960_000L, 1_000_000L, 1_020_000L, 1_080_000L}) {
            listing(Map.of("priceAmount", price, "firstRegistrationMonth", 7));
        }
        value(Map.of()).andExpect(jsonPath("$.estimate.medianAmount").value(1_000_000));

        String listed = listing(Map.of("priceAmount", 1_000_000L, "firstRegistrationMonth", 7));
        mockMvc.perform(get("/api/v1/listings/" + listed))
                .andExpect(jsonPath("$.priceIndicator.medianPriceAmount").value(1_000_000));
    }

    @Test
    @DisplayName("with four comparables there is no figure, and the loosenings that would give one are offered")
    void tooFew() throws Exception {
        for (int i = 0; i < 4; i++) {
            listing(Map.of());
        }
        // Each would be the fifth under one loosening, and only under that one.
        listing(Map.of("fuelType", "DIESEL", "powerHp", 130));   // another engine
        listing(Map.of("firstRegistrationYear", 2016));          // four years off
        listing(Map.of("mileageKm", 75_000));                    // another mileage band

        value(Map.of())
                .andExpect(jsonPath("$.comparableCount").value(4))
                .andExpect(jsonPath("$.estimate").doesNotExist())
                .andExpect(jsonPath("$.basis.yearFrom").value(2020))
                .andExpect(jsonPath("$.widerScopes.length()").value(3))
                .andExpect(jsonPath("$.widerScopes[*].scope",
                        Matchers.containsInAnyOrder("ALL_ENGINES", "WIDER_YEARS", "ANY_MILEAGE")))
                .andExpect(jsonPath("$.widerScopes[*].comparableCount", Matchers.everyItem(Matchers.is(5))));

        // Asked for, a loosening says it is one, and what it no longer matches on.
        value(Map.of("scope", "ALL_ENGINES"))
                .andExpect(jsonPath("$.scope").value("ALL_ENGINES"))
                .andExpect(jsonPath("$.comparableCount").value(5))
                .andExpect(jsonPath("$.estimate.medianAmount").value(1_000_000))
                .andExpect(jsonPath("$.window.fuelType").doesNotExist())
                .andExpect(jsonPath("$.window.powerFromHp").doesNotExist());
        value(Map.of("scope", "ANY_MILEAGE"))
                .andExpect(jsonPath("$.window.mileageFromKm").doesNotExist())
                .andExpect(jsonPath("$.basis.mileageToKm").value(75_000));
        value(Map.of("scope", "WIDER_YEARS"))
                .andExpect(jsonPath("$.window.yearFrom").value(2016))
                .andExpect(jsonPath("$.window.yearTo").value(2024));
    }

    @Test
    @DisplayName("a loosening that still finds fewer than five is not offered, and with nothing at all nothing is said")
    void nothingToOffer() throws Exception {
        listing(Map.of("fuelType", "DIESEL", "powerHp", 130));

        value(Map.of())
                .andExpect(jsonPath("$.comparableCount").value(0))
                .andExpect(jsonPath("$.estimate").doesNotExist())
                .andExpect(jsonPath("$.basis").doesNotExist())
                .andExpect(jsonPath("$.widerScopes.length()").value(0));
    }

    @Test
    @DisplayName("only live used listings of the model count, as for the indicator")
    void whatCounts() throws Exception {
        for (int i = 0; i < 4; i++) {
            listing(Map.of());
        }
        listing(Map.of("condition", "NEW", "mileageKm", 10, "firstRegistrationYear", 2020));
        act(listing(Map.of()), "sold");
        listing(Map.of("modelId", catalogue.threeSeries().getId().toString(),
                "makeId", catalogue.bmw().getId().toString()));

        value(Map.of()).andExpect(jsonPath("$.comparableCount").value(4));
    }

    @Test
    @DisplayName("the engines to choose from are the model's listed ones, the most listed first, with their usual name")
    void engines() throws Exception {
        listing(Map.of("versionText", "PureTech 100 Allure"));
        listing(Map.of("versionText", "PureTech 100 Allure"));
        listing(Map.of("versionText", "PureTech 100 Active"));
        listing(Map.of("fuelType", "DIESEL", "powerHp", 130, "versionText", "BlueHDi 130"));
        listing(Map.of("fuelType", "ELECTRIC", "powerHp", 136, "condition", "NEW", "mileageKm", 10));   // new: not a market

        mockMvc.perform(get("/api/v1/valuation/engines").param("modelId", catalogue.twoOhEight().getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fuelType").value("PETROL"))
                .andExpect(jsonPath("$[0].powerHp").value(100))
                .andExpect(jsonPath("$[0].exampleVersion").value("PureTech 100 Allure"))
                .andExpect(jsonPath("$[0].listingCount").value(3))
                .andExpect(jsonPath("$[1].fuelType").value("DIESEL"))
                .andExpect(jsonPath("$[1].listingCount").value(1));
    }

    @Test
    @DisplayName("needs no account, and refuses what it cannot value")
    void requests() throws Exception {
        // No Authorization header anywhere in this class: both endpoints are public.
        value(Map.of()).andExpect(status().isOk());

        value(Map.of("modelId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/valuation/engines").param("modelId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        value(Map.of("scope", "EVERYTHING")).andExpect(status().isBadRequest());
        value(Map.of("mileageKm", "-1")).andExpect(status().isBadRequest());

        Map<String, Object> noPower = new HashMap<>(defaults());
        noPower.remove("powerHp");
        mockMvc.perform(withParams(get("/api/v1/valuation"), noPower)).andExpect(status().isBadRequest());
    }

    // ---- helpers ------------------------------------------------------------------------

    /** La Cote for a 208 at the reference, with these parameters changed. */
    private ResultActions value(Map<String, Object> overrides) throws Exception {
        Map<String, Object> params = new HashMap<>(defaults());
        params.putAll(overrides);
        return mockMvc.perform(withParams(get("/api/v1/valuation"), params));
    }

    private Map<String, Object> defaults() {
        return Map.of(
                "modelId", catalogue.twoOhEight().getId().toString(),
                "fuelType", "PETROL",
                "powerHp", 100,
                "year", 2020,
                "month", 7,
                "mileageKm", 40_000);
    }

    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, Map<String, Object> params) {
        params.forEach((name, value) -> request.param(name, String.valueOf(value)));
        return request;
    }

    /** A published Peugeot 208 at the reference, with these fields changed. */
    private String listing(Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.remove("makeText");
        payload.remove("modelText");
        payload.put("makeId", catalogue.peugeot().getId().toString());
        payload.put("modelId", catalogue.twoOhEight().getId().toString());
        payload.put("title", "Peugeot 208");
        payload.put("fuelType", "PETROL");
        payload.put("powerHp", 100);
        payload.put("firstRegistrationYear", 2020);
        payload.put("firstRegistrationMonth", 7);
        payload.put("mileageKm", 40_000);
        payload.put("priceAmount", 1_000_000L);
        payload.putAll(overrides);
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        attachReadyPhoto(id);
        act(id, "publish");
        return id;
    }

    private void act(String listingId, String action) throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/" + action)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
    }
}
