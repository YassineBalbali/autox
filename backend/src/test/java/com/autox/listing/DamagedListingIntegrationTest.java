package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Damaged vehicles: declared by the seller, out of search unless asked for, and never part of
 * what a price is compared with.
 *
 * <p>The market is Peugeot 208s, petrol, 100 ch, 2020, 40 000 km, 10 000 € unless said otherwise.
 */
class DamagedListingIntegrationTest extends AbstractIntegrationTest {

    private static final Map<String, Object> DAMAGE = Map.of(
            "damaged", true,
            "types", List.of("BODYWORK", "HAIL"),
            "running", false,
            "description", "Choc avant, capot et phare à remplacer.");

    @Autowired ListingTestFixtures fixtures;

    private JsonNode seller;
    private ListingTestFixtures.Catalogue catalogue;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        seller = register(uniqueEmail("damage"), UserRole.PROFESSIONNEL);
    }

    // ---- helpers -------------------------------------------------------------------------

    private ResultActions create(Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.putAll(overrides);
        return mockMvc.perform(post("/api/v1/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    /** A published Peugeot 208 at the reference, with these fields changed. */
    private String listing(Map<String, Object> overrides) throws Exception {
        Map<String, Object> merged = new HashMap<>(Map.of(
                "makeId", catalogue.peugeot().getId().toString(),
                "modelId", catalogue.twoOhEight().getId().toString(),
                "title", "Peugeot 208",
                "fuelType", "PETROL",
                "powerHp", 100,
                "firstRegistrationYear", 2020,
                "firstRegistrationMonth", 7,
                "mileageKm", 40_000,
                "priceAmount", 1_000_000L));
        merged.putAll(overrides);
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.remove("makeText");
        payload.remove("modelText");
        payload.putAll(merged);
        String id = json(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        return id;
    }

    private String damaged(long price) throws Exception {
        return listing(Map.of("damage", DAMAGE, "priceAmount", price));
    }

    private void market(int intact) throws Exception {
        for (int i = 0; i < intact; i++) {
            listing(Map.of());
        }
    }

    private List<String> searchIds(String query) throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/v1/listings" + query)).andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private ResultActions edit(String id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/listings/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode admin() throws Exception {
        String email = uniqueEmail("moderator");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));
    }

    private String approved(Map<String, Object> overrides) throws Exception {
        String id = listing(overrides);
        mockMvc.perform(post("/api/v1/admin/listings/" + id + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        return id;
    }

    private String verdictOf(String id) {
        return jdbcTemplate.queryForObject("SELECT moderation_status FROM listing WHERE id = ?::uuid", String.class, id);
    }

    // ---- declaring -----------------------------------------------------------------------

    @Nested
    @DisplayName("declaring")
    class Declaring {

        @Test
        @DisplayName("a listing is undamaged unless its seller says otherwise")
        void undamagedByDefault() throws Exception {
            create(Map.of())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.damage").doesNotExist());
        }

        @Test
        @DisplayName("a declaration is kept, in a stable order, and shown back")
        void declared() throws Exception {
            create(Map.of("damage", DAMAGE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.damage.types[0]").value("BODYWORK"))
                    .andExpect(jsonPath("$.damage.types[1]").value("HAIL"))
                    .andExpect(jsonPath("$.damage.running").value(false))
                    .andExpect(jsonPath("$.damage.description").value("Choc avant, capot et phare à remplacer."));
        }

        static List<Map<String, Object>> incomplete() {
            return List.of(
                    Map.of("damaged", true, "types", List.of("HAIL"), "running", true),
                    Map.of("damaged", true, "types", List.of("HAIL"), "running", true, "description", "   "),
                    Map.of("damaged", true, "types", List.of(), "running", true, "description", "Grêle."),
                    Map.of("damaged", true, "running", true, "description", "Grêle."),
                    Map.of("damaged", true, "types", List.of("HAIL"), "description", "Grêle."),
                    Map.of("types", List.of("HAIL"), "running", true, "description", "Grêle."));
        }

        @ParameterizedTest
        @MethodSource("com.autox.listing.DamagedListingIntegrationTest$Declaring#incomplete")
        @DisplayName("a damaged vehicle needs a type, whether it runs, and a description")
        void incompleteIsRefused(Map<String, Object> damage) throws Exception {
            create(Map.of("damage", damage))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("a damage type that does not exist is refused, and so is the old DAMAGED condition")
        void unknownValues() throws Exception {
            create(Map.of("damage", Map.of("damaged", true, "types", List.of("RUST"), "running", true, "description", "x")))
                    .andExpect(status().isBadRequest());
            create(Map.of("condition", "DAMAGED")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the database holds the rule too, whatever writes to it")
        void databaseRule() throws Exception {
            String id = json(create(Map.of()).andReturn()).get("id").asText();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE listing SET damaged = TRUE WHERE id = ?::uuid", id))
                    .hasMessageContaining("ck_listing_damage");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE listing SET vehicle_condition = 'DAMAGED' WHERE id = ?::uuid", id))
                    .hasMessageContaining("ck_listing_condition");
        }
    }

    // ---- the filter ----------------------------------------------------------------------

    @Nested
    @DisplayName("the search filter")
    class Filter {

        @Test
        @DisplayName("left out, a search has no damaged vehicles; it can ask for them alone, or for all")
        void defaultExcludes() throws Exception {
            String intact = listing(Map.of());
            String wreck = damaged(300_000L);

            assertThat(searchIds("")).containsExactly(intact);
            assertThat(searchIds("?damage=UNDAMAGED")).containsExactly(intact);
            assertThat(searchIds("?damage=DAMAGED_ONLY")).containsExactly(wreck);
            assertThat(searchIds("?damage=ALL")).containsExactlyInAnyOrder(intact, wreck);
            mockMvc.perform(get("/api/v1/listings?damage=NOPE")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the count agrees with the list")
        void count() throws Exception {
            listing(Map.of());
            damaged(300_000L);

            mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/v1/listings/count?damage=DAMAGED_ONLY")).andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/v1/listings/count?damage=ALL")).andExpect(jsonPath("$.count").value(2));
        }

        @Test
        @DisplayName("a result card carries the declaration, and an undamaged one none")
        void cards() throws Exception {
            String wreck = damaged(300_000L);
            String intact = listing(Map.of());

            mockMvc.perform(get("/api/v1/listings?damage=ALL"))
                    .andExpect(jsonPath("$.items[?(@.id == '" + wreck + "')].damage.running").value(false))
                    .andExpect(jsonPath("$.items[?(@.id == '" + wreck + "')].damage.description")
                            .value("Choc avant, capot et phare à remplacer."))
                    .andExpect(jsonPath("$.items[?(@.id == '" + intact + "')].damage").value(org.hamcrest.Matchers.empty()));
        }

        @Test
        @DisplayName("its page stays open to anyone with the link, and says so")
        void detail() throws Exception {
            String wreck = damaged(300_000L);

            mockMvc.perform(get("/api/v1/listings/" + wreck))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.damage.types.length()").value(2))
                    .andExpect(jsonPath("$.damage.running").value(false));
        }

        @Test
        @DisplayName("search suggestions do not lead to a model that only has damaged vehicles")
        void suggestions() throws Exception {
            damaged(300_000L);

            mockMvc.perform(get("/api/v1/catalogue/suggestions?q=208"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("an intact car's similar listings have no damaged ones, and a damaged car's no intact ones")
        void similar() throws Exception {
            // Three of each besides the one viewed, because the section shows three or nothing.
            // Another year each: two listings of one seller for the same car count as one.
            List<String> intact = new ArrayList<>();
            List<String> wrecks = new ArrayList<>();
            for (int year : new int[] {2019, 2020, 2021, 2022}) {
                intact.add(listing(Map.of("firstRegistrationYear", year)));
                wrecks.add(listing(Map.of("damage", DAMAGE, "firstRegistrationYear", year)));
            }

            String aroundIntact = mockMvc.perform(get("/api/v1/listings/" + intact.get(1) + "/similar"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(aroundIntact).contains(intact.get(0), intact.get(2), intact.get(3));
            wrecks.forEach(wreck -> assertThat(aroundIntact).doesNotContain(wreck));

            String aroundWreck = mockMvc.perform(get("/api/v1/listings/" + wrecks.get(1) + "/similar"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(aroundWreck).contains(wrecks.get(0), wrecks.get(2), wrecks.get(3));
            intact.forEach(car -> assertThat(aroundWreck).doesNotContain(car));
        }
    }

    // ---- the market ----------------------------------------------------------------------

    @Nested
    @DisplayName("the price indicator and La Cote")
    class Market {

        private ResultActions value() throws Exception {
            return mockMvc.perform(get("/api/v1/valuation")
                    .param("modelId", catalogue.twoOhEight().getId().toString())
                    .param("fuelType", "PETROL").param("powerHp", "100")
                    .param("year", "2020").param("month", "7").param("mileageKm", "40000"));
        }

        @Test
        @DisplayName("damaged cars do not move an intact car's median, however cheap they are")
        void notComparablesOfTheIndicator() throws Exception {
            market(5);
            for (int i = 0; i < 6; i++) {
                damaged(200_000L);
            }
            String judged = listing(Map.of("priceAmount", 1_000_000L));

            mockMvc.perform(get("/api/v1/listings/" + judged))
                    .andExpect(jsonPath("$.priceIndicator.level").value("MARKET"))
                    .andExpect(jsonPath("$.priceIndicator.medianPriceAmount").value(1_000_000))
                    .andExpect(jsonPath("$.priceIndicator.comparableCount").value(5));
        }

        @Test
        @DisplayName("they do not make up the five comparables an indicator needs")
        void notCountedTowardsTheMinimum() throws Exception {
            market(4);
            for (int i = 0; i < 6; i++) {
                damaged(1_000_000L);
            }
            String judged = listing(Map.of());

            mockMvc.perform(get("/api/v1/listings/" + judged))
                    .andExpect(jsonPath("$.priceIndicator").doesNotExist());
        }

        @Test
        @DisplayName("a damaged car never gets a verdict, not even at a fifth of the market price")
        void noVerdict() throws Exception {
            market(6);
            String wreck = damaged(200_000L);

            mockMvc.perform(get("/api/v1/listings/" + wreck))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.priceIndicator").doesNotExist());
            mockMvc.perform(get("/api/v1/listings?damage=DAMAGED_ONLY"))
                    .andExpect(jsonPath("$.items[0].priceIndicator").doesNotExist());
        }

        @Test
        @DisplayName("a damaged car is never a good deal, even in a search that includes them")
        void neverAGoodDeal() throws Exception {
            market(6);
            damaged(200_000L);

            assertThat(searchIds("?damage=ALL&priceLevel=GOOD_DEAL")).isEmpty();
            assertThat(searchIds("?damage=DAMAGED_ONLY&priceLevel=GOOD_DEAL")).isEmpty();
        }

        @Test
        @DisplayName("La Cote ignores them: same median, same count, same range")
        void notComparablesOfLaCote() throws Exception {
            for (long price : new long[] {900_000L, 950_000L, 1_000_000L, 1_050_000L, 1_100_000L}) {
                listing(Map.of("priceAmount", price));
            }
            for (int i = 0; i < 6; i++) {
                damaged(100_000L);
            }

            value()
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.comparableCount").value(5))
                    .andExpect(jsonPath("$.estimate.medianAmount").value(1_000_000))
                    .andExpect(jsonPath("$.estimate.lowAmount").value(950_000))
                    .andExpect(jsonPath("$.estimate.highAmount").value(1_050_000));
        }

        @Test
        @DisplayName("La Cote does not offer an engine only damaged cars have")
        void notInLaCotesEngines() throws Exception {
            listing(Map.of("powerHp", 100));
            listing(Map.of("powerHp", 200, "damage", DAMAGE));

            mockMvc.perform(get("/api/v1/valuation/engines").param("modelId", catalogue.twoOhEight().getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].powerHp").value(100));
        }
    }

    // ---- moderation ----------------------------------------------------------------------

    @Nested
    @DisplayName("moderation")
    class Moderation {

        private static final Map<String, Object> UNDAMAGED = Map.of("damaged", false);

        @Test
        @DisplayName("going from damaged to undamaged sends the listing back to the queue, online")
        void damagedToUndamaged() throws Exception {
            String id = approved(Map.of("damage", DAMAGE));

            edit(id, Map.of("damage", UNDAMAGED))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.damage").doesNotExist());

            assertThat(verdictOf(id)).isEqualTo("NOT_REVIEWED");
            // It now competes with intact cars, and shows in a default search.
            assertThat(searchIds("")).contains(id);
            mockMvc.perform(get("/api/v1/admin/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                    .andExpect(jsonPath("$.editedSinceReview").value(true))
                    .andExpect(jsonPath("$.listing.damage").doesNotExist());
        }

        @Test
        @DisplayName("so do declaring damage, and changing what was declared")
        void otherChanges() throws Exception {
            String undamaged = approved(Map.of());
            edit(undamaged, Map.of("damage", DAMAGE)).andExpect(status().isOk());
            assertThat(verdictOf(undamaged)).isEqualTo("NOT_REVIEWED");

            String described = approved(Map.of("damage", DAMAGE));
            Map<String, Object> changed = new HashMap<>(DAMAGE);
            changed.put("running", true);
            edit(described, Map.of("damage", changed)).andExpect(status().isOk());
            assertThat(verdictOf(described)).isEqualTo("NOT_REVIEWED");
        }

        @Test
        @DisplayName("saving the same declaration again does not, in whatever order the types come")
        void sameDeclaration() throws Exception {
            String id = approved(Map.of("damage", DAMAGE));

            Map<String, Object> same = new HashMap<>(DAMAGE);
            same.put("types", List.of("HAIL", "BODYWORK"));
            edit(id, Map.of("damage", same)).andExpect(status().isOk());
            edit(id, Map.of("title", "Peugeot 208")).andExpect(status().isOk());

            assertThat(verdictOf(id)).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("a moderator sees the declaration")
        void moderatorSees() throws Exception {
            String id = damaged(300_000L);

            mockMvc.perform(get("/api/v1/admin/listings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                    .andExpect(jsonPath("$.listing.damage.description").value("Choc avant, capot et phare à remplacer."));
        }
    }

    // ---- the dashboard -------------------------------------------------------------------

    @Test
    @DisplayName("the dashboard counts damaged listings online, also counted as published")
    void dashboard() throws Exception {
        listing(Map.of());
        damaged(300_000L);
        String sold = damaged(300_000L);
        jdbcTemplate.update("UPDATE listing SET status = 'SOLD' WHERE id = ?::uuid", sold);

        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listings.damaged.real").value(1))
                .andExpect(jsonPath("$.listings.damaged.demo").value(0))
                .andExpect(jsonPath("$.listings.published.real").value(2));
    }
}
