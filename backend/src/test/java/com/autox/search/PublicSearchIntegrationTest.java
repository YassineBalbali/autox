package com.autox.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Public search and detail: visibility, filters, sorting and keyset pagination. */
class PublicSearchIntegrationTest extends AbstractIntegrationTest {

    // --- visibility -------------------------------------------------------

    @Test
    @DisplayName("search needs no authentication and returns only published listings")
    void searchIsPublicAndShowsOnlyActiveListings() throws Exception {
        JsonNode seller = register(uniqueEmail("public-seller"), UserRole.PROFESSIONNEL);

        String live = createListing(seller, Map.of("title", "Visible one"));
        publish(seller, live);
        createListing(seller, Map.of("title", "Still a draft"));

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Visible one"))
                .andExpect(jsonPath("$.sort").value("NEWEST"));
    }

    @Test
    @DisplayName("paused, sold, expired and deleted listings all disappear from search")
    void nonActiveStatesAreInvisible() throws Exception {
        JsonNode seller = register(uniqueEmail("states"), UserRole.PROFESSIONNEL);

        String paused = publishedListing(seller, "To be paused");
        String sold = publishedListing(seller, "To be sold");
        String deleted = publishedListing(seller, "To be deleted");
        String expired = publishedListing(seller, "To expire");
        String visible = publishedListing(seller, "Stays visible");

        act(seller, paused, "unpublish");
        act(seller, sold, "sold");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/listings/" + deleted)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNoContent());
        // Past its expiry but not yet swept: must already be invisible.
        jdbcTemplate.update("UPDATE listing SET expires_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), expired);

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visible));
    }

    @Test
    @DisplayName("a rejected listing is invisible even while ACTIVE")
    void rejectedListingsAreInvisible() throws Exception {
        JsonNode seller = register(uniqueEmail("rejected"), UserRole.PROFESSIONNEL);
        String id = publishedListing(seller, "Moderated away");

        jdbcTemplate.update("UPDATE listing SET moderation_status = 'REJECTED' WHERE id = ?::uuid", id);

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(status().isNotFound());
    }

    // --- detail -----------------------------------------------------------

    @Test
    @DisplayName("public detail needs no authentication and omits private fields")
    void publicDetailOmitsPrivateFields() throws Exception {
        JsonNode seller = register(uniqueEmail("detail-seller"), UserRole.PROFESSIONNEL);
        String id = createListing(seller, Map.of(
                "title", "BMW 320d", "vin", "WBA8E9G52GNT12345",
                "featureCodes", List.of("BLUETOOTH", "NAVIGATION")));
        publish(seller, id);

        String body = mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("BMW 320d"))
                .andExpect(jsonPath("$.vehicle.make").value("BMW"))
                .andExpect(jsonPath("$.location.city").value("Paris"))
                .andExpect(jsonPath("$.seller.displayName").isNotEmpty())
                .andExpect(jsonPath("$.seller.role").value("PROFESSIONNEL"))
                .andExpect(jsonPath("$.featureCodes.length()").value(2))
                .andExpect(jsonPath("$.images").isArray())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("WBA8E9G52GNT12345")   // VIN
                .doesNotContain("moderationStatus")
                .doesNotContain("latitude")
                .doesNotContain("viewCount")
                .doesNotContain("@autox.test");        // seller email
    }

    @Test
    @DisplayName("a draft is 404 publicly but readable by its owner under /mine")
    void draftIsPrivateButOwnerCanRead() throws Exception {
        JsonNode seller = register(uniqueEmail("draft-owner"), UserRole.PARTICULIER);
        String id = createListing(seller, Map.of("title", "Not yet live"));

        mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/listings/mine/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("even the owner gets 404 from the public detail endpoint for their own draft")
    void publicEndpointDoesNotLeakToTheOwnerEither() throws Exception {
        JsonNode seller = register(uniqueEmail("own-draft"), UserRole.PARTICULIER);
        String id = createListing(seller, Map.of("title", "Mine, unpublished"));

        mockMvc.perform(get("/api/v1/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isNotFound());
    }

    // --- filters ----------------------------------------------------------

    @Test
    @DisplayName("filters narrow by make, year, price, mileage, fuel and gearbox")
    void filtersNarrowResults() throws Exception {
        JsonNode seller = register(uniqueEmail("filters"), UserRole.PROFESSIONNEL);

        publishedListing(seller, Map.of("title", "Cheap old diesel", "makeText", "Peugeot",
                "modelText", "208", "firstRegistrationYear", 2012, "priceAmount", 450000L,
                "mileageKm", 180000, "fuelType", "DIESEL", "gearbox", "MANUAL"));
        publishedListing(seller, Map.of("title", "Recent petrol auto", "makeText", "BMW",
                "modelText", "3 Series", "firstRegistrationYear", 2021, "priceAmount", 3200000L,
                "mileageKm", 25000, "fuelType", "PETROL", "gearbox", "AUTOMATIC"));
        publishedListing(seller, Map.of("title", "Mid diesel auto", "makeText", "BMW",
                "modelText", "3 Series", "firstRegistrationYear", 2018, "priceAmount", 1800000L,
                "mileageKm", 90000, "fuelType", "DIESEL", "gearbox", "AUTOMATIC"));

        mockMvc.perform(get("/api/v1/listings").param("make", "BMW"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/listings").param("yearFrom", "2018"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/listings").param("yearFrom", "2018").param("yearTo", "2019"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Mid diesel auto"));
        mockMvc.perform(get("/api/v1/listings").param("priceMax", "1000000"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Cheap old diesel"));
        mockMvc.perform(get("/api/v1/listings").param("mileageMax", "100000"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "DIESEL"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "DIESEL").param("gearbox", "AUTOMATIC"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Mid diesel auto"));
        // Repeated parameters are an OR within the filter.
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "DIESEL", "PETROL"))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    @DisplayName("make filtering works for catalogue listings and free-text listings alike")
    void makeFilterSpansBothIdentificationPaths() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
        JsonNode seller = register(uniqueEmail("both-paths"), UserRole.PROFESSIONNEL);

        Map<String, Object> catalogued = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        catalogued.remove("makeText");
        catalogued.remove("modelText");
        catalogued.put("title", "From the catalogue");
        catalogued.put("makeId", catalogue.bmw().getId().toString());
        catalogued.put("modelId", catalogue.threeSeries().getId().toString());
        publish(seller, create(seller, catalogued));

        publishedListing(seller, Map.of("title", "From free text", "makeText", "BMW"));

        // The name filter sees both.
        mockMvc.perform(get("/api/v1/listings").param("make", "BMW"))
                .andExpect(jsonPath("$.items.length()").value(2));

        // The id filter sees only the catalogued one — which is why both exist.
        mockMvc.perform(get("/api/v1/listings").param("makeId", catalogue.bmw().getId().toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("From the catalogue"));
    }

    @Test
    @DisplayName("location matches city or postal code")
    void locationFilter() throws Exception {
        JsonNode seller = register(uniqueEmail("located"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "In Paris", "city", "Paris", "postalCode", "75011"));
        publishedListing(seller, Map.of("title", "In Lyon", "city", "Lyon", "postalCode", "69003"));

        mockMvc.perform(get("/api/v1/listings").param("location", "lyon"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("In Lyon"));
        mockMvc.perform(get("/api/v1/listings").param("location", "750"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("In Paris"));
    }

    @Test
    @DisplayName("a half-specified bounding box is rejected rather than ignored")
    void partialBoundingBoxIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("latMin", "48.0").param("latMax", "49.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unknown enum value is a 400, not a silent empty page")
    void unknownEnumValueIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "KEROSENE"))
                .andExpect(status().isBadRequest());
    }

    // --- sorting ----------------------------------------------------------

    @Test
    @DisplayName("every supported sort orders results correctly")
    void sortingWorks() throws Exception {
        JsonNode seller = register(uniqueEmail("sorter"), UserRole.PROFESSIONNEL);

        publishedListing(seller, Map.of("title", "B", "priceAmount", 2000000L, "mileageKm", 50000));
        publishedListing(seller, Map.of("title", "A", "priceAmount", 1000000L, "mileageKm", 150000));
        publishedListing(seller, Map.of("title", "C", "priceAmount", 3000000L, "mileageKm", 10000));

        assertThat(titles("PRICE_ASC")).containsExactly("A", "B", "C");
        assertThat(titles("PRICE_DESC")).containsExactly("C", "B", "A");
        assertThat(titles("MILEAGE_ASC")).containsExactly("C", "B", "A");
        // Newest first: C was published last.
        assertThat(titles("NEWEST")).containsExactly("C", "A", "B");
    }

    // --- keyset pagination ------------------------------------------------

    @Test
    @DisplayName("paging by cursor walks the whole result set exactly once")
    void cursorPaginationCoversEverythingWithoutRepeats() throws Exception {
        JsonNode seller = register(uniqueEmail("pager"), UserRole.PROFESSIONNEL);
        for (int i = 0; i < 11; i++) {
            publishedListing(seller, Map.of("title", "Listing " + i, "priceAmount", 1000000L + (i * 10000)));
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;

        do {
            var request = get("/api/v1/listings").param("size", "4");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode page = objectMapper.readTree(mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());

            page.get("items").forEach(item ->
                    assertThat(seen.add(item.get("id").asText())).as("no id appears twice").isTrue());

            cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).hasSize(11);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("a cursor from one sort order is refused by another")
    void cursorIsBoundToItsSort() throws Exception {
        JsonNode seller = register(uniqueEmail("cursor-mix"), UserRole.PROFESSIONNEL);
        for (int i = 0; i < 3; i++) {
            publishedListing(seller, Map.of("title", "L" + i, "priceAmount", 1000000L + i));
        }

        JsonNode firstPage = objectMapper.readTree(mockMvc.perform(get("/api/v1/listings")
                        .param("sort", "PRICE_ASC").param("size", "1"))
                .andReturn().getResponse().getContentAsString());
        String priceCursor = firstPage.get("nextCursor").asText();

        mockMvc.perform(get("/api/v1/listings").param("sort", "NEWEST").param("cursor", priceCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));

        mockMvc.perform(get("/api/v1/listings").param("sort", "PRICE_ASC").param("cursor", priceCursor))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a malformed cursor is a clean 400")
    void malformedCursorIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("the last page reports no further cursor")
    void lastPageHasNoCursor() throws Exception {
        JsonNode seller = register(uniqueEmail("short"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Only one"));

        mockMvc.perform(get("/api/v1/listings").param("size", "10"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("an oversized page is refused")
    void pageSizeIsCapped() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("results carry the cover photo, not every photo")
    void resultsCarryTheCoverImage() throws Exception {
        JsonNode seller = register(uniqueEmail("covered"), UserRole.PROFESSIONNEL);
        String id = publishedListing(seller, Map.of("title", "With a photo"));
        jdbcTemplate.update("""
                INSERT INTO listing_image_variant (id, image_id, variant, storage_key, width, height, byte_size)
                SELECT ?, i.id, 'THUMBNAIL', 'k/thumb.jpg', 320, 240, 4000
                  FROM listing_image i WHERE i.listing_id = ?::uuid
                """, com.autox.common.UuidV7.generate(), id);

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(jsonPath("$.items[0].coverImage.THUMBNAIL.url").isNotEmpty())
                .andExpect(jsonPath("$.items[0].coverImage.THUMBNAIL.width").value(320));
    }

    // --- language neutrality ----------------------------------------------

    @Test
    @DisplayName("responses carry stable codes, never display text, whatever language is asked for")
    void responsesAreLanguageNeutral() throws Exception {
        JsonNode seller = register(uniqueEmail("neutral"), UserRole.PROFESSIONNEL);
        String id = publishedListing(seller, Map.of(
                "title", "BMW 320d", "fuelType", "DIESEL", "gearbox", "AUTOMATIC",
                "bodyType", "SEDAN", "condition", "USED", "exteriorColor", "BLACK"));

        String french = mockMvc.perform(get("/api/v1/listings")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR"))
                .andExpect(jsonPath("$.items[0].fuelType").value("DIESEL"))
                .andExpect(jsonPath("$.items[0].gearbox").value("AUTOMATIC"))
                .andExpect(jsonPath("$.items[0].bodyType").value("SEDAN"))
                .andExpect(jsonPath("$.items[0].condition").value("USED"))
                .andReturn().getResponse().getContentAsString();

        String english = mockMvc.perform(get("/api/v1/listings")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en-GB"))
                .andReturn().getResponse().getContentAsString();

        // Identical bytes: nothing in a listing response varies with the requested language.
        assertThat(french).isEqualTo(english);

        mockMvc.perform(get("/api/v1/listings/" + id).header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR"))
                .andExpect(jsonPath("$.vehicle.fuelType").value("DIESEL"))
                .andExpect(jsonPath("$.exteriorColor").value("BLACK"))
                .andExpect(jsonPath("$.condition").value("USED"));
    }

    // --- free text --------------------------------------------------------

    @Test
    @DisplayName("free text matches the title, the make, the model and the version")
    void freeTextSearchesFourFields() throws Exception {
        JsonNode seller = register(uniqueEmail("q-fields"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Belle occasion à saisir", "makeText", "Peugeot",
                "modelText", "308", "versionText", "BlueHDi 130 Allure"));
        publishedListing(seller, Map.of("title", "Citadine économique", "makeText", "Renault",
                "modelText", "Clio", "versionText", "TCe 90"));

        // Each of the four, and none of them is the title of the first listing.
        for (String term : List.of("saisir", "peugeot", "308", "bluehdi")) {
            mockMvc.perform(get("/api/v1/listings").param("q", term))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].title").value("Belle occasion à saisir"));
        }
        mockMvc.perform(get("/api/v1/listings").param("q", "tesla"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("every word has to match, so a second word narrows the search")
    void freeTextRequiresEveryWord() throws Exception {
        JsonNode seller = register(uniqueEmail("q-words"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Golf diesel", "makeText", "Volkswagen",
                "modelText", "Golf", "versionText", "2.0 TDI 150"));
        publishedListing(seller, Map.of("title", "Golf essence", "makeText", "Volkswagen",
                "modelText", "Golf", "versionText", "1.5 TSI 130"));
        publishedListing(seller, Map.of("title", "Passat diesel", "makeText", "Volkswagen",
                "modelText", "Passat", "versionText", "2.0 TDI 150"));

        mockMvc.perform(get("/api/v1/listings").param("q", "golf"))
                .andExpect(jsonPath("$.items.length()").value(2));
        // "Any word" would return all three here, and typing more would find more.
        mockMvc.perform(get("/api/v1/listings").param("q", "golf tdi"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Golf diesel"));
        // Words in different fields, in any order, with stray spacing.
        mockMvc.perform(get("/api/v1/listings").param("q", "  TDI   volkswagen  golf "))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("LIKE wildcards in the text are matched literally, not as wildcards")
    void freeTextEscapesWildcards() throws Exception {
        JsonNode seller = register(uniqueEmail("q-escape"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Remise 100% garantie"));
        publishedListing(seller, Map.of("title", "Kilométrage 1000 km"));
        publishedListing(seller, Map.of("title", "Modele_special"));

        // Unescaped, "%" matches everything and "100%" matches "1000 km".
        mockMvc.perform(get("/api/v1/listings").param("q", "%"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/listings").param("q", "100%"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Remise 100% garantie"));
        // "_" means "any one character" to LIKE; here it must mean an underscore.
        mockMvc.perform(get("/api/v1/listings").param("q", "modele_special"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/listings").param("q", "modele-special"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("free text is bound as a parameter — an injection attempt is just a string")
    void freeTextIsNotInterpolated() throws Exception {
        JsonNode seller = register(uniqueEmail("q-inject"), UserRole.PROFESSIONNEL);
        publishedListing(seller, "Une annonce");

        mockMvc.perform(get("/api/v1/listings").param("q", "'; DROP TABLE listing; --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("free text combines with the other filters, and the count agrees")
    void freeTextCombinesAndIsCounted() throws Exception {
        JsonNode seller = register(uniqueEmail("q-combine"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Golf diesel", "fuelType", "DIESEL"));
        publishedListing(seller, Map.of("title", "Golf essence", "fuelType", "PETROL"));

        mockMvc.perform(get("/api/v1/listings").param("q", "golf").param("fuelType", "DIESEL"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/listings/count").param("q", "golf"))
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(get("/api/v1/listings/count").param("q", "golf").param("fuelType", "DIESEL"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("a blank query is no filter, and an over-long one is refused")
    void freeTextBounds() throws Exception {
        JsonNode seller = register(uniqueEmail("q-bounds"), UserRole.PROFESSIONNEL);
        publishedListing(seller, "Une annonce");

        mockMvc.perform(get("/api/v1/listings").param("q", "   "))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/listings").param("q", "x".repeat(121)))
                .andExpect(status().isBadRequest());
    }

    // --- count ------------------------------------------------------------

    @Test
    @DisplayName("the count is public and matches what search would return")
    void countMatchesSearch() throws Exception {
        JsonNode seller = register(uniqueEmail("count"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "Diesel one", "fuelType", "DIESEL"));
        publishedListing(seller, Map.of("title", "Diesel two", "fuelType", "DIESEL"));
        publishedListing(seller, Map.of("title", "Petrol", "fuelType", "PETROL"));
        // Not visible, so not counted: the count has to apply the same visibility rule as
        // the list, or the figure above the results disagrees with the results.
        createListing(seller, Map.of("title", "Draft diesel", "fuelType", "DIESEL"));

        mockMvc.perform(get("/api/v1/listings/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.capped").value(false));

        mockMvc.perform(get("/api/v1/listings/count").param("fuelType", "DIESEL"))
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(get("/api/v1/listings/count").param("fuelType", "ELECTRIC"))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.capped").value(false));
    }

    @Test
    @DisplayName("the count takes the same filters as search, including make and location")
    void countHonoursJoinedFilters() throws Exception {
        // Make and location read through joins the count query has to carry too. Dropping
        // them from the count as an optimisation would silently count everything.
        JsonNode seller = register(uniqueEmail("count-joins"), UserRole.PROFESSIONNEL);
        publishedListing(seller, Map.of("title", "A", "makeText", "Peugeot", "city", "Lyon",
                "postalCode", "69003"));
        publishedListing(seller, Map.of("title", "B", "makeText", "Renault", "city", "Nantes",
                "postalCode", "44000"));

        mockMvc.perform(get("/api/v1/listings/count").param("make", "Peugeot"))
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(get("/api/v1/listings/count").param("location", "Nantes"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("counting stops at the cap and says so")
    void countIsBounded() throws Exception {
        // The property that makes the endpoint safe to expose: the scan is bounded by the
        // cap, not by the table. Exercised on the repository with a cap of 2, since nobody
        // wants a test that inserts ten thousand listings to reach the real one.
        JsonNode seller = register(uniqueEmail("count-cap"), UserRole.PROFESSIONNEL);
        for (int i = 0; i < 4; i++) {
            publishedListing(seller, "Capped " + i);
        }
        ListingSearchCriteria all = objectMapper.readValue("{}", ListingSearchCriteria.class);

        // One past the cap and no further: 4 rows match, 3 are counted.
        assertThat(searchRepository.countUpTo(all, 2)).isEqualTo(3);
        assertThat(searchRepository.countUpTo(all, 10)).isEqualTo(4);
    }

    @Test
    @DisplayName("an invalid filter is rejected by the count exactly as by search")
    void countValidatesItsCriteria() throws Exception {
        mockMvc.perform(get("/api/v1/listings/count").param("fuelType", "STEAM"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/listings/count").param("latMin", "45.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- helpers ----------------------------------------------------------

    @org.springframework.beans.factory.annotation.Autowired ListingSearchRepository searchRepository;


    @org.springframework.beans.factory.annotation.Autowired ListingTestFixtures fixtures;

    private List<String> titles(String sort) throws Exception {
        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/v1/listings").param("sort", sort))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> titles = new ArrayList<>();
        page.get("items").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private String create(JsonNode session, Map<String, Object> payload) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createListing(JsonNode session, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.putAll(overrides);
        return create(session, payload);
    }

    private void publish(JsonNode session, String listingId) throws Exception {
        attachReadyPhoto(listingId);
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    private String publishedListing(JsonNode session, String title) throws Exception {
        return publishedListing(session, Map.of("title", title));
    }

    private String publishedListing(JsonNode session, Map<String, Object> overrides) throws Exception {
        String id = createListing(session, overrides);
        publish(session, id);
        return id;
    }

    private void act(JsonNode session, String listingId, String action) throws Exception {
        mockMvc.perform(post("/api/v1/listings/" + listingId + "/" + action)
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

}
