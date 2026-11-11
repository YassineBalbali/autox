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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Crit'Air, estimated from the fuel and the first registration by the official table (arrêté du
 * 21 juin 2016, annexe I), in the database function {@code crit_air_class} (V30). 0 is the green
 * vignette, 6 unclassified, an empty cell none: the date cannot settle it.
 */
class CritAirIntegrationTest extends AbstractIntegrationTest {

    private Integer classOf(String fuel, Integer year, Integer month) {
        return jdbcTemplate.queryForObject("SELECT public.crit_air_class(?, ?, ?)", Integer.class, fuel, year, month);
    }

    @Nested
    @DisplayName("the table")
    class Table {

        @ParameterizedTest(name = "petrol {1}-{2}: {3}")
        @DisplayName("petrol: unclassified, 3, 2, 1, by date, and nothing where the Euro standard would decide")
        @CsvSource(nullValues = "-", value = {
                // Before Euro 2: unclassified; the last month sure is December 1995.
                "PETROL, 1990, 6, 6",
                "PETROL, 1995, 12, 6",
                // Euro 2 new types from January 1996, every car from January 1997, end of series to December 1997.
                "PETROL, 1996, 1, -",
                "PETROL, 1997, 1, -",
                "PETROL, 1997, 12, -",
                // Euro 2 and 3: class 3, January 1997 to December 2005. The Euro 3 change is no change here.
                "PETROL, 1998, 1, 3",
                "PETROL, 2000, 6, 3",
                "PETROL, 2001, 1, 3",
                "PETROL, 2004, 12, 3",
                // Euro 4 window: January 2005 to December 2006.
                "PETROL, 2005, 1, -",
                "PETROL, 2006, 1, -",
                "PETROL, 2006, 12, -",
                // Euro 4: class 2, January 2006 to December 2010.
                "PETROL, 2007, 1, 2",
                "PETROL, 2009, 8, 2",
                // Euro 5 window: new types from September 2009, end of series to December 2011.
                "PETROL, 2009, 9, -",
                "PETROL, 2010, 12, -",
                "PETROL, 2011, 1, -",
                "PETROL, 2011, 12, -",
                // Euro 5 and 6: class 1 from January 2011.
                "PETROL, 2012, 1, 1",
                "PETROL, 2024, 6, 1",
        })
        void petrol(String fuel, int year, int month, Integer expected) {
            assertThat(classOf(fuel, year, month)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "diesel {1}-{2}: {3}")
        @DisplayName("diesel: unclassified, 5, 4, 3, 2, by date, and nothing where the Euro standard would decide")
        @CsvSource(nullValues = "-", value = {
                "DIESEL, 1995, 12, 6",
                "DIESEL, 1996, 6, -",
                "DIESEL, 1997, 12, -",
                // Euro 2: class 5, January 1997 to December 2000.
                "DIESEL, 1998, 1, 5",
                "DIESEL, 1999, 12, 5",
                // Euro 3 window: January 2000 to December 2001.
                "DIESEL, 2000, 1, -",
                "DIESEL, 2001, 1, -",
                "DIESEL, 2001, 12, -",
                // Euro 3: class 4, January 2001 to December 2005.
                "DIESEL, 2002, 1, 4",
                "DIESEL, 2004, 12, 4",
                "DIESEL, 2005, 1, -",
                "DIESEL, 2006, 12, -",
                // Euro 4: class 3, January 2006 to December 2010.
                "DIESEL, 2007, 1, 3",
                "DIESEL, 2009, 8, 3",
                "DIESEL, 2009, 9, -",
                "DIESEL, 2011, 1, -",
                "DIESEL, 2011, 12, -",
                // Euro 5 and 6: class 2 from January 2011.
                "DIESEL, 2012, 1, 2",
                "DIESEL, 2023, 3, 2",
        })
        void diesel(String fuel, int year, int month, Integer expected) {
            assertThat(classOf(fuel, year, month)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} {1}: {3}")
        @DisplayName("each fuel: hybrids by their engine, gas and plug-in hybrids 1, electric and hydrogen 0, whatever the date")
        @CsvSource(nullValues = "-", value = {
                "HYBRID_PETROL, 2015, 6, 1",
                "HYBRID_PETROL, 2008, 6, 2",
                "HYBRID_DIESEL, 2015, 6, 2",
                "HYBRID_DIESEL, 2010, 6, -",
                "PLUGIN_HYBRID, 2008, 6, 1",
                "PLUGIN_HYBRID, 2023, 6, 1",
                "LPG, 1995, 6, 1",
                "LPG, 2018, 6, 1",
                "CNG, 2003, 6, 1",
                "ELECTRIC, 2020, 6, 0",
                "ELECTRIC, 1999, 6, 0",
                "HYDROGEN, 2022, 6, 0",
        })
        void fuels(String fuel, int year, int month, Integer expected) {
            assertThat(classOf(fuel, year, month)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} {1} without a month: {2}")
        @DisplayName("without a month, a year is settled only if every month of it is")
        @CsvSource(nullValues = "-", value = {
                "PETROL, 2008, 2",
                "PETROL, 2009, -",
                "PETROL, 2012, 1",
                "DIESEL, 2003, 4",
                "DIESEL, 2000, -",
                "DIESEL, 2015, 2",
                "PLUGIN_HYBRID, 2010, 1",
        })
        void yearOnly(String fuel, int year, Integer expected) {
            assertThat(classOf(fuel, year, null)).isEqualTo(expected);
        }

        @Test
        @DisplayName("nothing for a fuel the table does not name, or without a date for petrol and diesel")
        void unknown() {
            assertThat(classOf("ETHANOL", 2018, 6)).isNull();
            assertThat(classOf("OTHER", 2018, 6)).isNull();
            assertThat(classOf(null, 2018, 6)).isNull();
            assertThat(classOf("PETROL", null, null)).isNull();
            assertThat(classOf("DIESEL", null, 6)).isNull();
        }
    }

    // ---- on listings ---------------------------------------------------------------------------

    @Test
    @DisplayName("a listing's page gives the estimate, kept up to date when the car's date changes, and none when unsure")
    void onTheListing() throws Exception {
        JsonNode seller = register(uniqueEmail("crit-air"), UserRole.PROFESSIONNEL);
        String diesel = listing(seller, Map.of("fuelType", "DIESEL", "firstRegistrationYear", 2019));
        String ethanol = listing(seller, Map.of("fuelType", "ETHANOL", "firstRegistrationYear", 2019));
        String unsure = listing(seller, Map.of("fuelType", "PETROL", "firstRegistrationYear", 2010));
        String electric = listing(seller, Map.of("fuelType", "ELECTRIC", "firstRegistrationYear", 2022));
        String leased = listing(seller, Map.of("fuelType", "HYBRID_PETROL", "firstRegistrationYear", 2023,
                "offer", Map.of("offerType", "LEASING", "type", "LLD", "monthlyPayment", 30_000L,
                        "firstPayment", 100_000L, "durationMonths", 36, "mileagePerYear", 10_000)));

        detail(diesel).andExpect(jsonPath("$.critAir").value("TWO"));
        detail(electric).andExpect(jsonPath("$.critAir").value("ZERO"));
        detail(leased).andExpect(jsonPath("$.critAir").value("ONE"));
        detail(ethanol).andExpect(status().isOk()).andExpect(jsonPath("$.critAir").doesNotExist());
        detail(unsure).andExpect(status().isOk()).andExpect(jsonPath("$.critAir").doesNotExist());

        mockMvc.perform(patch("/api/v1/listings/" + diesel).header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("firstRegistrationYear", 2004))))
                .andExpect(status().is2xxSuccessful());
        detail(diesel).andExpect(jsonPath("$.critAir").value("FOUR"));
    }

    @Test
    @DisplayName("'Crit'Air max' keeps that class and better, and leaves out the unclassified and the unknown")
    void filter() throws Exception {
        JsonNode seller = register(uniqueEmail("crit-air-filter"), UserRole.PROFESSIONNEL);
        String electric = listing(seller, Map.of("fuelType", "ELECTRIC", "firstRegistrationYear", 2022));
        String petrol = listing(seller, Map.of("fuelType", "PETROL", "firstRegistrationYear", 2018));
        String diesel = listing(seller, Map.of("fuelType", "DIESEL", "firstRegistrationYear", 2018));
        String oldDiesel = listing(seller, Map.of("fuelType", "DIESEL", "firstRegistrationYear", 2008));
        String unclassified = listing(seller, Map.of("fuelType", "PETROL", "firstRegistrationYear", 1992));
        String unknown = listing(seller, Map.of("fuelType", "ETHANOL", "firstRegistrationYear", 2018));
        String leased = listing(seller, Map.of("fuelType", "DIESEL", "firstRegistrationYear", 2024,
                "offer", Map.of("offerType", "LEASING", "type", "LLD", "monthlyPayment", 30_000L,
                        "firstPayment", 100_000L, "durationMonths", 36, "mileagePerYear", 10_000)));

        assertThat(ids("?critAirMax=0")).containsExactly(electric);
        assertThat(ids("?critAirMax=1")).containsExactlyInAnyOrder(electric, petrol);
        assertThat(ids("?critAirMax=2")).containsExactlyInAnyOrder(electric, petrol, diesel);
        assertThat(ids("?critAirMax=5")).containsExactlyInAnyOrder(electric, petrol, diesel, oldDiesel)
                .doesNotContain(unclassified, unknown);
        assertThat(ids("")).contains(unclassified, unknown);
        assertThat(ids("?offer=LEASING&critAirMax=2")).containsExactly(leased);
        assertThat(ids("?offer=LEASING&critAirMax=1")).isEmpty();
        mockMvc.perform(get("/api/v1/listings/count?critAirMax=2")).andExpect(jsonPath("$.count").value(3));
        mockMvc.perform(get("/api/v1/listings?critAirMax=6")).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions detail(String id) throws Exception {
        return mockMvc.perform(get("/api/v1/listings/" + id));
    }

    private List<String> ids(String query) throws Exception {
        List<String> ids = new ArrayList<>();
        json(mockMvc.perform(get("/api/v1/listings" + query)).andExpect(status().isOk()).andReturn())
                .get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    /** A published listing, the fixture's car with these changes; no month, unless one is given. */
    private String listing(JsonNode owner, Map<String, Object> overrides) throws Exception {
        Map<String, Object> payload = new HashMap<>(ListingTestFixtures.freeTextListingPayload());
        payload.remove("firstRegistrationMonth");
        payload.putAll(overrides);
        String id = json(mockMvc.perform(post("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().is2xxSuccessful());
        return id;
    }
}
