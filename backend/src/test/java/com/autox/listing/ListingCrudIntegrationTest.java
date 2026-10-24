package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** Create, read, update, delete and the either/or identification rules. */
class ListingCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingTestFixtures fixtures;
    @Autowired ListingRepository listings;

    // --- create -----------------------------------------------------------

    @Test
    @DisplayName("a new listing is created as a DRAFT and is not live")
    void createProducesDraft() throws Exception {
        JsonNode seller = register(uniqueEmail("seller"), UserRole.PARTICULIER);

        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.moderationStatus").value("NOT_REVIEWED"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.priceAmount").value(1899000))
                .andExpect(jsonPath("$.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.allowedTransitions").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(body);
        assertThat(created.get("sellerId").asText()).isEqualTo(seller.get("user").get("id").asText());
        // Free-text paths are reported as unresolved so the client can show a hint.
        assertThat(created.get("location").get("resolved").asBoolean()).isFalse();
        assertThat(created.get("location").get("city").asText()).isEqualTo("Paris");
        assertThat(created.get("vehicle").get("fromCatalogue").asBoolean()).isFalse();
        assertThat(created.get("vehicle").get("make").asText()).isEqualTo("BMW");
    }

    @Test
    @DisplayName("a listing can reference the catalogue instead of free text")
    void createWithCatalogueReference() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
        JsonNode seller = register(uniqueEmail("cat-seller"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.remove("makeText");
        payload.remove("modelText");
        payload.put("makeId", catalogue.bmw().getId().toString());
        payload.put("modelId", catalogue.threeSeries().getId().toString());
        payload.put("trimId", catalogue.threeTwentyD().getId().toString());

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vehicle.fromCatalogue").value(true))
                .andExpect(jsonPath("$.vehicle.make").value("BMW"))
                .andExpect(jsonPath("$.vehicle.model").value("3 Series"))
                .andExpect(jsonPath("$.vehicle.trim").value("320d xDrive"));
    }

    @Test
    @DisplayName("a gazetteer reference fills in the coordinates")
    void createWithGeoPlaceReference() throws Exception {
        UUID parisId = fixtures.ensureParis().getId();
        JsonNode seller = register(uniqueEmail("geo-seller"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.remove("postalCode");
        payload.remove("city");
        payload.remove("countryCode");
        payload.put("geoPlaceId", parisId.toString());

        String id = objectMapper.readTree(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location.resolved").value(true))
                .andExpect(jsonPath("$.location.city").value("Paris"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        Listing stored = listings.findActiveById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getLatitude()).isNotNull();
        assertThat(stored.getLongitude()).isNotNull();
    }

    @Test
    @DisplayName("supplying both a catalogue id and free text for the make is rejected")
    void makeCannotBeGivenTwice() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
        JsonNode seller = register(uniqueEmail("xor-both"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.put("makeId", catalogue.bmw().getId().toString());
        payload.put("modelId", catalogue.threeSeries().getId().toString());
        // makeText and modelText are still present.

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("supplying neither a catalogue id nor free text for the make is rejected")
    void makeMustBeGivenSomehow() throws Exception {
        JsonNode seller = register(uniqueEmail("xor-neither"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.remove("makeText");

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a model from a different make is rejected with a named reason")
    void modelMustBelongToMake() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
        JsonNode seller = register(uniqueEmail("mismatch"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.remove("makeText");
        payload.remove("modelText");
        payload.put("makeId", catalogue.bmw().getId().toString());
        payload.put("modelId", catalogue.twoOhEight().getId().toString()); // a Peugeot

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("modelId does not belong to makeId."));
    }

    @Test
    @DisplayName("a listing with no location at all is rejected")
    void locationIsRequired() throws Exception {
        JsonNode seller = register(uniqueEmail("no-location"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.remove("postalCode");
        payload.remove("city");

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("out-of-range values are rejected per field, not by the database")
    void rangesAreValidated() throws Exception {
        JsonNode seller = register(uniqueEmail("ranges"), UserRole.PARTICULIER);

        Map<String, Object> payload = ListingTestFixtures.freeTextListingPayload();
        payload.put("firstRegistrationYear", 1800);
        payload.put("mileageKm", -5);
        payload.put("priceAmount", -1);

        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'firstRegistrationYear')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'mileageKm')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'priceAmount')]").exists());
    }

    @Test
    @DisplayName("a new listing needs its power, a whole number of ch from 1 to 2 000")
    void powerIsRequired() throws Exception {
        JsonNode seller = register(uniqueEmail("power"), UserRole.PARTICULIER);

        Map<String, Object> missing = ListingTestFixtures.freeTextListingPayload();
        missing.remove("powerHp");
        create(seller, missing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'powerHp')]").exists());

        for (int power : new int[] {0, 2001}) {
            Map<String, Object> outOfRange = ListingTestFixtures.freeTextListingPayload();
            outOfRange.put("powerHp", power);
            create(seller, outOfRange)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'powerHp')]").exists());
        }
    }

    @Test
    @DisplayName("a listing saved before the power was asked for still loads, edits and publishes, and can be given one")
    void listingWithoutPower() throws Exception {
        JsonNode seller = register(uniqueEmail("no-power"), UserRole.PARTICULIER);
        String id = createListing(seller);
        // As a listing created before the field was required has it.
        jdbcTemplate.update("UPDATE listing SET power_hp = NULL, power_kw = NULL WHERE id = ?", UUID.fromString(id));

        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicle.powerHp").doesNotExist());
        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priceAmount", 1_700_000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicle.powerHp").doesNotExist());

        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicle.powerHp").doesNotExist());

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("powerHp", 190, "powerKw", 140))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicle.powerHp").value(190))
                .andExpect(jsonPath("$.vehicle.powerKw").value(140));
    }

    @Test
    @DisplayName("creating a listing requires authentication")
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("equipment codes are resolved, and an unknown code is rejected")
    void featureCodesAreValidated() throws Exception {
        JsonNode seller = register(uniqueEmail("features"), UserRole.PARTICULIER);

        Map<String, Object> good = ListingTestFixtures.freeTextListingPayload();
        good.put("featureCodes", List.of("BLUETOOTH", "NAVIGATION", "HEATED_SEATS"));
        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(good)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.featureCodes.length()").value(3))
                .andExpect(jsonPath("$.featureCodes[0]").value("BLUETOOTH"));

        Map<String, Object> bad = ListingTestFixtures.freeTextListingPayload();
        bad.put("featureCodes", List.of("BLUETOOTH", "EJECTOR_SEAT"));
        mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown feature codes: [EJECTOR_SEAT]."));
    }

    // --- read -------------------------------------------------------------

    @Test
    @DisplayName("the seller's list is paginated and filterable by status")
    void listOwnIsPaginated() throws Exception {
        JsonNode seller = register(uniqueEmail("lister"), UserRole.PARTICULIER);
        for (int i = 0; i < 3; i++) {
            createListing(seller);
        }

        mockMvc.perform(get("/api/v1/listings/mine")
                        .param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/v1/listings/mine")
                        .param("status", "ACTIVE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("an oversized page request is refused")
    void pageSizeIsCapped() throws Exception {
        JsonNode seller = register(uniqueEmail("bigpage"), UserRole.PARTICULIER);

        mockMvc.perform(get("/api/v1/listings/mine")
                        .param("size", "1000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a seller's list contains only their own listings")
    void listOwnIsScopedToTheSeller() throws Exception {
        JsonNode alice = register(uniqueEmail("alice-l"), UserRole.PARTICULIER);
        JsonNode bob = register(uniqueEmail("bob-l"), UserRole.PARTICULIER);
        createListing(alice);
        createListing(alice);
        createListing(bob);

        mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- update -----------------------------------------------------------

    @Test
    @DisplayName("PATCH changes only the fields supplied")
    void patchUpdatesSuppliedFieldsOnly() throws Exception {
        JsonNode seller = register(uniqueEmail("patcher"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "priceAmount", 1750000L,
                                "mileageKm", 91000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceAmount").value(1750000))
                .andExpect(jsonPath("$.mileageKm").value(91000))
                // Untouched.
                .andExpect(jsonPath("$.title").value("BMW 320d xDrive M Sport"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("PATCH cannot set the status directly — transitions have their own endpoints")
    void patchCannotSetStatus() throws Exception {
        JsonNode seller = register(uniqueEmail("status-patcher"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"moderationStatus\":\"APPROVED\",\"title\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.moderationStatus").value("NOT_REVIEWED"));
    }

    @Test
    @DisplayName("switching from free text to a catalogue reference clears the text")
    void patchCanSwitchToCatalogue() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
        JsonNode seller = register(uniqueEmail("switcher"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(patch("/api/v1/listings/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "makeId", catalogue.peugeot().getId().toString(),
                                "modelId", catalogue.twoOhEight().getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicle.fromCatalogue").value(true))
                .andExpect(jsonPath("$.vehicle.make").value("Peugeot"));

        Listing stored = listings.findActiveById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getMakeText()).isNull();
        assertThat(stored.getModelText()).isNull();
    }

    // --- delete -----------------------------------------------------------

    @Test
    @DisplayName("delete is a soft delete: the row survives but disappears from every read")
    void deleteIsSoft() throws Exception {
        JsonNode seller = register(uniqueEmail("deleter"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/listings/mine/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/listings/mine").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Still physically present, with the removal recorded.
        Listing row = listings.findById(UUID.fromString(id)).orElseThrow();
        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(ListingStatus.REMOVED);
    }

    @Test
    @DisplayName("deleting twice is not an error")
    void deleteIsIdempotent() throws Exception {
        JsonNode seller = register(uniqueEmail("double-deleter"), UserRole.PARTICULIER);
        String id = createListing(seller);

        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());
        // The row is gone from every read path, so a repeat is a 404 rather than a 500.
        mockMvc.perform(delete("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown listing id is a 404, not a 500")
    void unknownIdIsNotFound() throws Exception {
        JsonNode seller = register(uniqueEmail("ghost-listing"), UserRole.PARTICULIER);

        mockMvc.perform(get("/api/v1/listings/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.ResultActions create(JsonNode session, Map<String, Object> payload)
            throws Exception {
        return mockMvc.perform(post("/api/v1/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private String createListing(JsonNode session) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
