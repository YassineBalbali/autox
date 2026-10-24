package com.autox.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * "Annonces similaires" against a real PostgreSQL: the rule in {@link SimilarListings}, where
 * it is written as SQL, is the thing under test.
 *
 * <p>The listing looked at is a Peugeot 208 — hatchback, petrol, 100 ch, manual, 2020,
 * 40 000 km, 10 000 € unless said otherwise. Each listing made here gets a version of its own,
 * so that none is taken for another's copy unless a test means it to be.
 */
class SimilarListingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListingTestFixtures fixtures;

    private ListingTestFixtures.Catalogue catalogue;
    private JsonNode seller;
    private int versions;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        seller = register(uniqueEmail("similar"), UserRole.PROFESSIONNEL);
    }

    @Test
    @DisplayName("the same model first, within 3 years and 40 % of the price: its comparables, then the nearest year, then other engines")
    void sameModelBestFirst() throws Exception {
        String viewed = twoOhEight(Map.of());
        String sameEngineNextYear = twoOhEight(Map.of("firstRegistrationYear", 2021));
        String diesel = twoOhEight(Map.of("fuelType", "DIESEL", "powerHp", 100));
        String sameEngineThreeYearsOn = twoOhEight(Map.of("firstRegistrationYear", 2023));
        twoOhEight(Map.of("firstRegistrationYear", 2024, "priceAmount", 2_000_000L));   // four years: too far
        twoOhEight(Map.of("firstRegistrationYear", 2021, "priceAmount", 1_450_000L));   // 45 % dearer: another purchase

        // The diesel is the closest in age, but another engine: the comparables come first.
        assertThat(similar(viewed)).containsExactly(sameEngineNextYear, sameEngineThreeYearsOn, diesel);
    }

    @Test
    @DisplayName("each one is a result card, as search returns it")
    void resultCards() throws Exception {
        String viewed = twoOhEight(Map.of());
        for (int i = 0; i < 3; i++) {
            twoOhEight(Map.of("mileageKm", 41_000 + i));
        }

        JsonNode card = cards(viewed).get(0);
        assertThat(card.get("title").asText()).isEqualTo("Peugeot 208");
        assertThat(card.get("model").asText()).isEqualTo("208");
        assertThat(card.get("seller").get("displayName").asText()).isNotBlank();
        assertThat(card.get("photoCount").asInt()).isEqualTo(1);
        assertThat(card.has("coverImage")).isTrue();
    }

    @Test
    @DisplayName("then other models of the same body type within 20 % of the price, the same fuel first")
    void thenTheSameKindOfCarForTheMoney() throws Exception {
        String viewed = twoOhEight(Map.of());
        String other208 = twoOhEight(Map.of("mileageKm", 50_000));
        String petrolAtNineThousand = typedIn("Renault", "Clio", Map.of("priceAmount", 900_000L));
        String petrolAtElevenFive = threeSeries(Map.of("priceAmount", 1_150_000L));
        String dieselAtTenThousand = typedIn("Volkswagen", "Polo", Map.of("fuelType", "DIESEL"));
        typedIn("Seat", "Ibiza", Map.of("priceAmount", 1_250_000L));                    // 25 % dearer
        typedIn("Dacia", "Duster", Map.of("bodyType", "SUV"));                          // another body

        assertThat(similar(viewed))
                .containsExactly(other208, petrolAtNineThousand, petrolAtElevenFive, dieselAtTenThousand);
    }

    @Test
    @DisplayName("a listing typed in by hand has no model to share, and is matched by body and price alone")
    void typedInListing() throws Exception {
        String viewed = typedIn("Renault", "Clio", Map.of());
        String a = twoOhEight(Map.of("priceAmount", 950_000L));
        String b = typedIn("Renault", "Clio", Map.of("priceAmount", 1_060_000L));
        String c = typedIn("Volkswagen", "Polo", Map.of("priceAmount", 1_100_000L));

        assertThat(similar(viewed)).containsExactly(a, b, c);
    }

    @Test
    @DisplayName("six at most")
    void sixAtMost() throws Exception {
        String viewed = twoOhEight(Map.of());
        for (int i = 0; i < 8; i++) {
            twoOhEight(Map.of("mileageKm", 41_000 + i * 1_000));
        }

        assertThat(similar(viewed)).hasSize(SimilarListings.MAX_SHOWN);
    }

    @Test
    @DisplayName("fewer than three matches and nothing is shown, rather than padding")
    void noneRatherThanTwo() throws Exception {
        String viewed = twoOhEight(Map.of());
        twoOhEight(Map.of("mileageKm", 45_000));
        typedIn("Renault", "Clio", Map.of());
        typedIn("Dacia", "Duster", Map.of("bodyType", "SUV"));                          // not a match

        assertThat(similar(viewed)).isEmpty();
    }

    @Test
    @DisplayName("never the listing itself, and nothing sold, paused or deleted")
    void onlyLiveOthers() throws Exception {
        String viewed = twoOhEight(Map.of());
        List<String> live = List.of(twoOhEight(Map.of()), twoOhEight(Map.of()), twoOhEight(Map.of()));
        act(twoOhEight(Map.of()), "sold");
        act(twoOhEight(Map.of()), "unpublish");
        String deleted = twoOhEight(Map.of());
        mockMvc.perform(delete("/api/v1/listings/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().is2xxSuccessful());

        assertThat(similar(viewed)).containsExactlyInAnyOrderElementsOf(live);
    }

    @Test
    @DisplayName("one card per car: a seller's copies of a car once, and of the car being viewed not at all")
    void onePerCar() throws Exception {
        Map<String, Object> viewedCar = Map.of("versionText", "PureTech 100 Allure");
        Map<String, Object> stockCar = Map.of("versionText", "PureTech 100 Active", "firstRegistrationYear", 2021);

        String viewed = twoOhEight(viewedCar);
        twoOhEight(merge(viewedCar, Map.of("mileageKm", 42_000)));                     // the seller's copy of it
        String nearest = twoOhEight(merge(stockCar, Map.of("mileageKm", 40_500)));
        twoOhEight(merge(stockCar, Map.of("mileageKm", 47_000)));                      // the same car again
        twoOhEight(merge(stockCar, Map.of("mileageKm", 52_000)));                      // and again

        // The same car from somebody else is somebody else's car.
        seller = register(uniqueEmail("rival"), UserRole.PROFESSIONNEL);
        String rivals = twoOhEight(merge(viewedCar, Map.of("mileageKm", 44_000)));
        String rivalsOther = twoOhEight(merge(stockCar, Map.of("mileageKm", 39_000)));

        assertThat(similar(viewed)).containsExactly(rivals, nearest, rivalsOther);
    }

    @Test
    @DisplayName("404 for a listing that is not public, as for the listing itself")
    void notPublic() throws Exception {
        String draft = create(payload(Map.of()));

        mockMvc.perform(get("/api/v1/listings/" + draft + "/similar")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/listings/" + UUID.randomUUID() + "/similar")).andExpect(status().isNotFound());
    }

    // ---- helpers ------------------------------------------------------------------------

    private List<String> similar(String listingId) throws Exception {
        List<String> ids = new ArrayList<>();
        cards(listingId).forEach(card -> ids.add(card.get("id").asText()));
        return ids;
    }

    private JsonNode cards(String listingId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/listings/" + listingId + "/similar"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** A published Peugeot 208 at the reference, with these fields changed. */
    private String twoOhEight(Map<String, Object> overrides) throws Exception {
        return publish(create(payload(overrides)));
    }

    /** A published BMW 3 Series from the catalogue, a hatchback here, otherwise at the reference. */
    private String threeSeries(Map<String, Object> overrides) throws Exception {
        return twoOhEight(merge(Map.of(
                "makeId", catalogue.bmw().getId().toString(),
                "modelId", catalogue.threeSeries().getId().toString(),
                "title", "BMW Série 3"), overrides));
    }

    /** A published listing of a car typed in by hand, otherwise at the reference. */
    private String typedIn(String make, String model, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = payload(overrides);
        payload.remove("makeId");
        payload.remove("modelId");
        payload.put("makeText", make);
        payload.put("modelText", model);
        payload.put("title", make + " " + model);
        return publish(create(payload));
    }

    private Map<String, Object> payload(Map<String, Object> overrides) {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.remove("makeText");
        payload.remove("modelText");
        payload.put("makeId", catalogue.peugeot().getId().toString());
        payload.put("modelId", catalogue.twoOhEight().getId().toString());
        payload.put("title", "Peugeot 208");
        payload.put("versionText", "Version " + ++versions);
        payload.put("bodyType", "HATCHBACK");
        payload.put("gearbox", "MANUAL");
        payload.put("fuelType", "PETROL");
        payload.put("powerHp", 100);
        payload.put("firstRegistrationYear", 2020);
        payload.put("mileageKm", 40_000);
        payload.put("priceAmount", 1_000_000L);
        payload.putAll(overrides);
        return payload;
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new HashMap<>(base);
        merged.putAll(overrides);
        return merged;
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
