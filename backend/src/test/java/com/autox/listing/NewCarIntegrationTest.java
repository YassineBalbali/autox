package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Neuf": a car in new condition from a professional seller. A private seller cannot list one
 * (enforced on the server, like leasing), and the search, the "Neuf / Occasion" filter and the
 * /neuf page's make counts all read the one rule, {@code ListingSearchRepository.soldNew}.
 */
class NewCarIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingTestFixtures fixtures;

    private ListingTestFixtures.Catalogue catalogue;
    private JsonNode dealer;
    private JsonNode privateSeller;

    @BeforeEach
    void accounts() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        dealer = register(uniqueEmail("neuf-pro"), UserRole.PROFESSIONNEL);
        privateSeller = register(uniqueEmail("neuf-particulier"), UserRole.PARTICULIER);
    }

    @Nested
    @DisplayName("who may sell a car as new")
    class Rule {

        @Test
        @DisplayName("a professional may: created, published, and shown as new")
        void professional() throws Exception {
            String id = published(dealer, Map.of("condition", "NEW", "mileageKm", 12));

            mockMvc.perform(get("/api/v1/listings/" + id))
                    .andExpect(jsonPath("$.condition").value("NEW"))
                    .andExpect(jsonPath("$.soldNew").value(true))
                    // La Cote is for used cars: a new one is not judged against the market.
                    .andExpect(jsonPath("$.priceIndicator").doesNotExist());
        }

        @Test
        @DisplayName("a private seller may not: refused on creating and on editing, with its own code")
        void privateSeller() throws Exception {
            create(privateSeller, Map.of("condition", "NEW"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NEW_CAR_PROFESSIONAL_ONLY"));

            String used = json(create(privateSeller, Map.of()).andExpect(status().isCreated()).andReturn())
                    .get("id").asText();
            mockMvc.perform(patch("/api/v1/listings/" + used).header(HttpHeaders.AUTHORIZATION, bearer(privateSeller))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("condition", "NEW"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NEW_CAR_PROFESSIONAL_ONLY"));
            assertThat(jdbcTemplate.queryForObject("SELECT vehicle_condition FROM listing WHERE id = ?::uuid",
                    String.class, used)).isEqualTo("USED");
        }

        @Test
        @DisplayName("a private 'new' car from before the rule cannot go online again, and is used meanwhile")
        void fromBeforeTheRule() throws Exception {
            String id = published(privateSeller, Map.of());
            jdbcTemplate.update("UPDATE listing SET vehicle_condition = 'NEW' WHERE id = ?::uuid", id);

            mockMvc.perform(get("/api/v1/listings/" + id)).andExpect(jsonPath("$.soldNew").value(false));
            mockMvc.perform(post("/api/v1/listings/" + id + "/renew")
                            .header(HttpHeaders.AUTHORIZATION, bearer(privateSeller)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NEW_CAR_PROFESSIONAL_ONLY"));
        }
    }

    @Nested
    @DisplayName("the 'Neuf / Occasion' filter")
    class Filter {

        @Test
        @DisplayName("neuf is new from a professional; occasion is everything else; both, or neither, is everything")
        void byTheRule() throws Exception {
            String brandNew = published(dealer, Map.of("condition", "NEW", "mileageKm", 10));
            String dealersUsed = published(dealer, Map.of());
            String privateUsed = published(privateSeller, Map.of());
            String privateNew = published(privateSeller, Map.of());
            jdbcTemplate.update("UPDATE listing SET vehicle_condition = 'NEW' WHERE id = ?::uuid", privateNew);

            assertThat(ids("condition=NEW")).containsExactly(brandNew);
            assertThat(ids("condition=USED")).containsExactlyInAnyOrder(dealersUsed, privateUsed, privateNew);
            assertThat(ids("condition=NEW&condition=USED")).hasSize(4);
            assertThat(ids("")).hasSize(4);
            count("condition=NEW").andExpect(jsonPath("$.count").value(1));
            count("condition=USED").andExpect(jsonPath("$.count").value(3));

            JsonNode card = json(mockMvc.perform(get("/api/v1/listings?condition=NEW")).andReturn()).get("items").get(0);
            assertThat(card.get("soldNew").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("the makes with new stock, as /neuf lists them")
    class Makes {

        @Test
        @DisplayName("each make's count is the search's count for that make, most first, catalogue makes only")
        void countsMatchTheSearch() throws Exception {
            published(dealer, peugeot(Map.of("condition", "NEW", "mileageKm", 5)));
            published(dealer, peugeot(Map.of("condition", "NEW", "mileageKm", 8)));
            published(dealer, bmw(Map.of("condition", "NEW", "mileageKm", 15)));
            // Not new stock: used, a private seller's, or typed by hand.
            published(dealer, peugeot(Map.of()));
            String privateNew = published(privateSeller, bmw(Map.of()));
            jdbcTemplate.update("UPDATE listing SET vehicle_condition = 'NEW' WHERE id = ?::uuid", privateNew);
            published(dealer, Map.of("condition", "NEW", "mileageKm", 3));

            JsonNode makes = json(mockMvc.perform(get("/api/v1/listings/makes?condition=NEW"))
                    .andExpect(status().isOk()).andReturn());

            assertThat(makes).hasSize(2);
            assertThat(makes.get(0).get("slug").asText()).isEqualTo(catalogue.peugeot().getSlug());
            assertThat(makes.get(0).get("count").asLong()).isEqualTo(2);
            assertThat(makes.get(1).get("slug").asText()).isEqualTo(catalogue.bmw().getSlug());
            assertThat(makes.get(1).get("count").asLong()).isEqualTo(1);
            count("condition=NEW&makeId=" + catalogue.peugeot().getId()).andExpect(jsonPath("$.count").value(2));
            count("condition=NEW&makeId=" + catalogue.bmw().getId()).andExpect(jsonPath("$.count").value(1));
            // The same filters as the search: a leasing search has no new stock here.
            assertThat(json(mockMvc.perform(get("/api/v1/listings/makes?condition=NEW&offer=LEASING")).andReturn()))
                    .isEmpty();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, Object> peugeot(Map<String, Object> overrides) {
        return catalogued(catalogue.peugeot().getId(), catalogue.twoOhEight().getId(), overrides);
    }

    private Map<String, Object> bmw(Map<String, Object> overrides) {
        return catalogued(catalogue.bmw().getId(), catalogue.threeSeries().getId(), overrides);
    }

    private static Map<String, Object> catalogued(UUID makeId, UUID modelId, Map<String, Object> overrides) {
        Map<String, Object> payload = new HashMap<>(overrides);
        payload.put("makeId", makeId.toString());
        payload.put("modelId", modelId.toString());
        payload.put("makeText", null);
        payload.put("modelText", null);
        return payload;
    }

    private ResultActions create(JsonNode owner, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.putAll(overrides);
        payload.values().removeIf(java.util.Objects::isNull);
        return mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)));
    }

    private String published(JsonNode owner, Map<String, Object> overrides) throws Exception {
        String id = json(create(owner, overrides).andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    private List<String> ids(String query) throws Exception {
        List<String> ids = new ArrayList<>();
        json(mockMvc.perform(get("/api/v1/listings?" + query)).andExpect(status().isOk()).andReturn())
                .get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private ResultActions count(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/listings/count?" + query)).andExpect(status().isOk());
    }
}
