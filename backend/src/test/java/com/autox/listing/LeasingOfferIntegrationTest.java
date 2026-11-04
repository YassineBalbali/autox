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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Leasing offers: dealers only, the total worked out by the server, a search of their own, and no
 * part in what a sale's price is compared with.
 *
 * <p>The market is Peugeot 208s, petrol, 100 ch, 2020, 40 000 km, 10 000 € unless said otherwise.
 */
class LeasingOfferIntegrationTest extends AbstractIntegrationTest {

    /** 2 000 € then 36 × 250 €, then a 10 000 € option: 21 000 €. */
    private static final Map<String, Object> LOA = Map.of(
            "offerType", "LEASING",
            "type", "LOA",
            "monthlyPayment", 25_000L,
            "firstPayment", 200_000L,
            "durationMonths", 37,
            "mileagePerYear", 15_000,
            "optionPrice", 1_000_000L);

    /** 1 000 € then 35 × 300 €: 11 500 €. */
    private static final Map<String, Object> LLD = Map.of(
            "offerType", "LEASING",
            "type", "LLD",
            "monthlyPayment", 30_000L,
            "firstPayment", 100_000L,
            "durationMonths", 36,
            "mileagePerYear", 10_000);

    @Autowired ListingTestFixtures fixtures;

    private JsonNode dealer;
    private ListingTestFixtures.Catalogue catalogue;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = fixtures.ensureCatalogue();
        dealer = register(uniqueEmail("dealer"), UserRole.PROFESSIONNEL);
    }

    private ResultActions create(JsonNode seller, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.putAll(overrides);
        return mockMvc.perform(post("/api/v1/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private ResultActions edit(JsonNode seller, String id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/listings/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** A published Peugeot 208 at the reference, with these fields changed. */
    private String listing(Map<String, Object> overrides) throws Exception {
        Map<String, Object> merged = new HashMap<>();
        merged.put("makeText", null);
        merged.put("modelText", null);
        merged.putAll(Map.of(
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
        String id = json(create(dealer, merged).andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isOk());
        return id;
    }

    private List<String> searchIds(String query) throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/v1/listings" + query)).andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private Map<String, Object> loa(Map<String, Object> changes) {
        Map<String, Object> terms = new HashMap<>(LOA);
        terms.putAll(changes);
        return terms;
    }

    // ---- who may offer it ----------------------------------------------------------------

    @Nested
    @DisplayName("dealers only")
    class ProfessionalOnly {

        @Test
        @DisplayName("a private seller cannot create a leasing offer, and a dealer can")
        void create() throws Exception {
            JsonNode person = register(uniqueEmail("person"), UserRole.PARTICULIER);

            LeasingOfferIntegrationTest.this.create(person, Map.of("offer", LOA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LEASING_PROFESSIONAL_ONLY"));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isZero();

            LeasingOfferIntegrationTest.this.create(dealer, Map.of("offer", LOA)).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("a private seller cannot turn a sale into one")
        void update() throws Exception {
            JsonNode person = register(uniqueEmail("person"), UserRole.PARTICULIER);
            String id = json(LeasingOfferIntegrationTest.this.create(person, Map.of())
                    .andExpect(status().isCreated()).andReturn()).get("id").asText();

            edit(person, id, Map.of("offer", LOA))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LEASING_PROFESSIONAL_ONLY"));
            assertThat(jdbcTemplate.queryForObject("SELECT offer_type FROM listing WHERE id = ?::uuid", String.class, id))
                    .isEqualTo("SALE");
        }

        @Test
        @DisplayName("an administrator editing a private seller's listing is held to it too")
        void adminEditing() throws Exception {
            JsonNode person = register(uniqueEmail("person"), UserRole.PARTICULIER);
            String id = json(LeasingOfferIntegrationTest.this.create(person, Map.of()).andReturn()).get("id").asText();
            String email = uniqueEmail("admin");
            createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
            JsonNode admin = json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));

            edit(admin, id, Map.of("offer", LOA)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a dealer who stops being one cannot put an offer back online")
        void demotedDealer() throws Exception {
            String id = json(LeasingOfferIntegrationTest.this.create(dealer, Map.of("offer", LOA)).andReturn()).get("id").asText();
            attachReadyPhoto(id);
            jdbcTemplate.update("UPDATE app_user SET role = 'PARTICULIER'");

            mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LEASING_PROFESSIONAL_ONLY"));
        }

        @Test
        @DisplayName("a private seller can still sell, and say so explicitly")
        void saleIsFine() throws Exception {
            JsonNode person = register(uniqueEmail("person"), UserRole.PARTICULIER);

            LeasingOfferIntegrationTest.this.create(person, Map.of("offer", Map.of("offerType", "SALE")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leasing").doesNotExist());
        }
    }

    // ---- the total -----------------------------------------------------------------------

    @Nested
    @DisplayName("the total cost")
    class TotalCost {

        @Test
        @DisplayName("LOA: first payment + monthly × (duration − 1) + option")
        void withOption() throws Exception {
            // 2 000 + 250 × 36 + 10 000.
            create(dealer, Map.of("offer", LOA))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leasing.totalCost").value(2_100_000));
        }

        @Test
        @DisplayName("LLD: first payment + monthly × (duration − 1), with no option")
        void withoutOption() throws Exception {
            // 1 000 + 300 × 35.
            create(dealer, Map.of("offer", LLD))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leasing.totalCost").value(1_150_000))
                    .andExpect(jsonPath("$.leasing.optionPrice").doesNotExist());
        }

        @Test
        @DisplayName("a first payment of zero is an offer, and a total")
        void noFirstPayment() throws Exception {
            create(dealer, Map.of("offer", loa(Map.of("firstPayment", 0L))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leasing.totalCost").value(1_900_000));
        }

        @Test
        @DisplayName("it cannot be typed: a total in the request is ignored")
        void cannotBeTyped() throws Exception {
            create(dealer, Map.of("offer", loa(Map.of("totalCost", 1L))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leasing.totalCost").value(2_100_000));
        }

        @Test
        @DisplayName("it follows every change of terms")
        void recomputed() throws Exception {
            String id = json(create(dealer, Map.of("offer", LOA)).andReturn()).get("id").asText();

            edit(dealer, id, Map.of("offer", loa(Map.of("monthlyPayment", 30_000L))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.leasing.totalCost").value(2_280_000));
            edit(dealer, id, Map.of("offer", Map.of("offerType", "SALE")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.leasing").doesNotExist());
            assertThat(jdbcTemplate.queryForObject("SELECT leasing_total_cost FROM listing WHERE id = ?::uuid", Long.class, id))
                    .isNull();
        }

        @Test
        @DisplayName("the database refuses a total that does not add up, whoever writes it")
        void databaseRule() throws Exception {
            String id = json(create(dealer, Map.of("offer", LOA)).andReturn()).get("id").asText();

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE listing SET leasing_total_cost = 1 WHERE id = ?::uuid", id))
                    .hasMessageContaining("ck_listing_leasing");
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE listing SET leasing_total_cost = NULL WHERE id = ?::uuid", id))
                    .hasMessageContaining("ck_listing_leasing");
        }
    }

    // ---- the declaration -----------------------------------------------------------------

    @Test
    @DisplayName("an offer needs all its terms: an LOA its option price, and an LLD none")
    void completeTerms() throws Exception {
        for (String field : List.of("type", "monthlyPayment", "firstPayment", "durationMonths", "mileagePerYear", "optionPrice")) {
            Map<String, Object> terms = new HashMap<>(LOA);
            terms.remove(field);
            create(dealer, Map.of("offer", terms)).andExpect(status().isBadRequest());
        }
        Map<String, Object> lldWithOption = new HashMap<>(LLD);
        lldWithOption.put("optionPrice", 100L);
        create(dealer, Map.of("offer", lldWithOption)).andExpect(status().isBadRequest());
        create(dealer, Map.of("offer", loa(Map.of("durationMonths", 3)))).andExpect(status().isBadRequest());
        create(dealer, Map.of("offer", loa(Map.of("monthlyPayment", 0L)))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a damaged vehicle cannot be leased")
    void notDamaged() throws Exception {
        create(dealer, Map.of("offer", LOA, "damage",
                Map.of("damaged", true, "types", List.of("HAIL"), "running", true, "description", "Grêle.")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("changing an offer sends it back to moderation; saving it unchanged does not")
    void moderation() throws Exception {
        String id = listing(Map.of("offer", LOA));
        jdbcTemplate.update("UPDATE listing SET moderation_status = 'APPROVED' WHERE id = ?::uuid", id);

        edit(dealer, id, Map.of("offer", LOA)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT moderation_status FROM listing WHERE id = ?::uuid", String.class, id))
                .isEqualTo("APPROVED");

        edit(dealer, id, Map.of("offer", loa(Map.of("monthlyPayment", 20_000L)))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT moderation_status FROM listing WHERE id = ?::uuid", String.class, id))
                .isEqualTo("NOT_REVIEWED");
    }

    // ---- search --------------------------------------------------------------------------

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("sales and leasing never mix, and a search with no choice is for sales")
        void neverMix() throws Exception {
            String sale = listing(Map.of());
            String lease = listing(Map.of("offer", LOA));

            assertThat(searchIds("")).containsExactly(sale);
            assertThat(searchIds("?offer=SALE")).containsExactly(sale);
            assertThat(searchIds("?offer=LEASING")).containsExactly(lease);
            // Not even when every other filter would let it through.
            assertThat(searchIds("?damage=ALL")).containsExactly(sale);
            mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/v1/listings/count?offer=LEASING")).andExpect(jsonPath("$.count").value(1));
        }

        @Test
        @DisplayName("a result carries its terms, and its page stays open by link")
        void results() throws Exception {
            String lease = listing(Map.of("offer", LOA));

            mockMvc.perform(get("/api/v1/listings?offer=LEASING"))
                    .andExpect(jsonPath("$.items[0].leasing.type").value("LOA"))
                    .andExpect(jsonPath("$.items[0].leasing.monthlyPayment").value(25_000))
                    .andExpect(jsonPath("$.items[0].leasing.totalCost").value(2_100_000));
            mockMvc.perform(get("/api/v1/listings/" + lease))
                    .andExpect(jsonPath("$.leasing.optionPrice").value(1_000_000))
                    .andExpect(jsonPath("$.priceAmount").value(1_000_000));
        }

        @Test
        @DisplayName("filters on the type, the monthly payment, the duration and the mileage")
        void filters() throws Exception {
            String cheapShort = listing(Map.of("offer", loa(Map.of("monthlyPayment", 20_000L, "durationMonths", 24, "mileagePerYear", 10_000))));
            String dearLong = listing(Map.of("offer", loa(Map.of("monthlyPayment", 40_000L, "durationMonths", 48, "mileagePerYear", 20_000))));
            String rental = listing(Map.of("offer", LLD));

            assertThat(searchIds("?offer=LEASING&leasingType=LLD")).containsExactly(rental);
            assertThat(searchIds("?offer=LEASING&monthlyMax=25000")).containsExactly(cheapShort);
            assertThat(searchIds("?offer=LEASING&monthlyMin=35000")).containsExactly(dearLong);
            assertThat(searchIds("?offer=LEASING&durationMax=24")).containsExactly(cheapShort);
            assertThat(searchIds("?offer=LEASING&durationMin=48")).containsExactly(dearLong);
            assertThat(searchIds("?offer=LEASING&mileageAllowanceMin=20000")).containsExactly(dearLong);
            assertThat(searchIds("?offer=LEASING&mileageAllowanceMax=10000")).contains(cheapShort, rental).doesNotContain(dearLong);
        }

        @Test
        @DisplayName("the terms of a lease filter nothing in a search for sales")
        void termsIgnoredOnSales() throws Exception {
            String sale = listing(Map.of());

            assertThat(searchIds("?monthlyMax=1")).containsExactly(sale);
        }

        @Test
        @DisplayName("sorts by monthly payment, duration and mileage, and pages through without repeats")
        void sorts() throws Exception {
            String a = listing(Map.of("offer", loa(Map.of("monthlyPayment", 20_000L, "durationMonths", 48, "mileagePerYear", 10_000))));
            String b = listing(Map.of("offer", loa(Map.of("monthlyPayment", 30_000L, "durationMonths", 24, "mileagePerYear", 20_000))));
            String c = listing(Map.of("offer", loa(Map.of("monthlyPayment", 40_000L, "durationMonths", 36, "mileagePerYear", 15_000))));

            assertThat(searchIds("?offer=LEASING&sort=MONTHLY_ASC")).containsExactly(a, b, c);
            assertThat(searchIds("?offer=LEASING&sort=MONTHLY_DESC")).containsExactly(c, b, a);
            assertThat(searchIds("?offer=LEASING&sort=DURATION_ASC")).containsExactly(b, c, a);
            assertThat(searchIds("?offer=LEASING&sort=MILEAGE_ALLOWANCE_DESC")).containsExactly(b, c, a);

            for (String sort : List.of("MONTHLY_ASC", "DURATION_ASC", "MILEAGE_ALLOWANCE_DESC")) {
                JsonNode first = json(mockMvc.perform(get("/api/v1/listings?offer=LEASING&size=2&sort=" + sort)).andReturn());
                String cursor = first.get("nextCursor").asText();
                JsonNode second = json(mockMvc.perform(get("/api/v1/listings?offer=LEASING&size=2&sort=" + sort + "&cursor=" + cursor))
                        .andExpect(status().isOk()).andReturn());
                assertThat(second.get("items")).hasSize(1);
            }
        }

        @Test
        @DisplayName("sorting sales by a lease's terms is refused, not silently wrong")
        void leasingSortOnSales() throws Exception {
            mockMvc.perform(get("/api/v1/listings?sort=MONTHLY_ASC"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("suggestions do not lead to a model that only has leasing offers")
        void suggestions() throws Exception {
            listing(Map.of("offer", LOA));

            mockMvc.perform(get("/api/v1/catalogue/suggestions?q=208"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("similar listings keep sales and leasing offers apart")
        void similar() throws Exception {
            List<String> sales = new ArrayList<>();
            List<String> leases = new ArrayList<>();
            for (int year : new int[] {2019, 2020, 2021, 2022}) {
                sales.add(listing(Map.of("firstRegistrationYear", year)));
                leases.add(listing(Map.of("offer", LOA, "firstRegistrationYear", year)));
            }

            String aroundSale = mockMvc.perform(get("/api/v1/listings/" + sales.get(1) + "/similar"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(aroundSale).contains(sales.get(0), sales.get(2), sales.get(3));
            leases.forEach(lease -> assertThat(aroundSale).doesNotContain(lease));

            String aroundLease = mockMvc.perform(get("/api/v1/listings/" + leases.get(1) + "/similar"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(aroundLease).contains(leases.get(0), leases.get(2), leases.get(3));
            sales.forEach(sale -> assertThat(aroundLease).doesNotContain(sale));
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

        private void market(int sales) throws Exception {
            for (int i = 0; i < sales; i++) {
                listing(Map.of());
            }
        }

        @Test
        @DisplayName("leasing offers do not move a sale's median, whatever their cash price")
        void notComparablesOfTheIndicator() throws Exception {
            market(5);
            for (int i = 0; i < 6; i++) {
                listing(Map.of("offer", LOA, "priceAmount", 200_000L));
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
                listing(Map.of("offer", LOA));
            }
            String judged = listing(Map.of());

            mockMvc.perform(get("/api/v1/listings/" + judged)).andExpect(jsonPath("$.priceIndicator").doesNotExist());
        }

        @Test
        @DisplayName("a leasing offer never gets a verdict, and is never a good deal")
        void noVerdict() throws Exception {
            market(6);
            String lease = listing(Map.of("offer", LOA, "priceAmount", 200_000L));

            mockMvc.perform(get("/api/v1/listings/" + lease)).andExpect(jsonPath("$.priceIndicator").doesNotExist());
            mockMvc.perform(get("/api/v1/listings?offer=LEASING"))
                    .andExpect(jsonPath("$.items[0].priceIndicator").doesNotExist());
            assertThat(searchIds("?offer=LEASING&priceLevel=GOOD_DEAL")).isEmpty();
        }

        @Test
        @DisplayName("La Cote ignores them: same median, same count, same range")
        void notComparablesOfLaCote() throws Exception {
            for (long price : new long[] {900_000L, 950_000L, 1_000_000L, 1_050_000L, 1_100_000L}) {
                listing(Map.of("priceAmount", price));
            }
            for (int i = 0; i < 6; i++) {
                listing(Map.of("offer", LOA, "priceAmount", 100_000L));
            }

            value()
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.comparableCount").value(5))
                    .andExpect(jsonPath("$.estimate.medianAmount").value(1_000_000))
                    .andExpect(jsonPath("$.estimate.lowAmount").value(950_000));
        }

        @Test
        @DisplayName("La Cote does not offer an engine only leasing offers have")
        void notInLaCotesEngines() throws Exception {
            listing(Map.of("powerHp", 100));
            listing(Map.of("powerHp", 200, "offer", LOA));

            mockMvc.perform(get("/api/v1/valuation/engines").param("modelId", catalogue.twoOhEight().getId().toString()))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].powerHp").value(100));
        }
    }

    // ---- the dashboard -------------------------------------------------------------------

    @Test
    @DisplayName("the dashboard counts sales and leasing offers separately, and they add up to published")
    void dashboard() throws Exception {
        listing(Map.of());
        listing(Map.of());
        listing(Map.of("offer", LOA));
        String email = uniqueEmail("admin");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode admin = json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));

        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.listings.published.real").value(3))
                .andExpect(jsonPath("$.listings.sale.real").value(2))
                .andExpect(jsonPath("$.listings.leasing.real").value(1));
    }
}
