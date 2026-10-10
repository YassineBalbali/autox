package com.autox.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The reference vocabularies. These exist so the Angular app never hardcodes a list the
 * database owns, so the tests that matter most are the ones asserting the endpoint really
 * is the single source: that it covers every enum the API can return, and every seeded
 * feature code.
 */
class ReferenceEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Autowired ListingTestFixtures fixtures;

    // --- access -----------------------------------------------------------

    @Test
    @DisplayName("every reference endpoint is readable without a token")
    void publicWithoutAuthentication() throws Exception {
        for (String path : List.of("/api/v1/reference/enums", "/api/v1/reference/features",
                "/api/v1/reference/makes", "/api/v1/reference/countries")) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("responses are publicly cacheable and carry an ETag")
    void cacheable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/reference/features"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(header().exists("ETag"))
                .andReturn();

        // The second call with the same ETag is answered 304 with no body, which is the point
        // of the filter: the Angular app re-requests these on every load.
        mockMvc.perform(get("/api/v1/reference/features")
                        .header("If-None-Match", result.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());
    }

    // --- enums ------------------------------------------------------------

    @Test
    @DisplayName("enums endpoint returns the constant names of every published type")
    void enumsReturnConstants() throws Exception {
        JsonNode values = json(mockMvc.perform(get("/api/v1/reference/enums"))
                .andExpect(status().isOk()).andReturn()).get("values");

        assertThat(values.has("FuelType")).isTrue();
        assertThat(names(values.get("FuelType"))).contains("DIESEL", "PETROL", "ELECTRIC");
        assertThat(names(values.get("Gearbox"))).containsExactlyInAnyOrder(
                java.util.Arrays.stream(com.autox.catalog.Gearbox.values()).map(Enum::name)
                        .toArray(String[]::new));
        assertThat(names(values.get("SearchSort")))
                .containsExactly("NEWEST", "PRICE_ASC", "PRICE_DESC", "MILEAGE_ASC");
    }

    @Test
    @DisplayName("ErrorCode is published, because clients are told to branch on it")
    void errorCodesArePublished() throws Exception {
        JsonNode values = json(mockMvc.perform(get("/api/v1/reference/enums")).andReturn())
                .get("values");

        assertThat(names(values.get("ErrorCode")))
                .contains("VALIDATION_FAILED", "PHOTO_REQUIRED", "INVALID_CURSOR", "RATE_LIMITED");
    }

    /**
     * The drift guard, and the reason this endpoint exists at all.
     *
     * <p>Every enum that appears in a request or response type must be published, or a client
     * building its dropdowns from this endpoint silently has no value for it. The scan covers
     * the DTO and criteria types by name because those, and only those, cross the wire —
     * entity enums such as {@code TokenRevocationReason} are internal and deliberately absent.
     */
    @Test
    @DisplayName("every enum reachable from a request or response type is published")
    void publishedSetCoversTheWireContract() throws Exception {
        Set<String> published = new TreeSet<>();
        json(mockMvc.perform(get("/api/v1/reference/enums")).andReturn())
                .get("values").fieldNames().forEachRemaining(published::add);

        Set<String> onTheWire = new TreeSet<>();
        for (Class<?> type : wireTypes()) {
            collectEnums(type, onTheWire);
        }

        assertThat(onTheWire).isNotEmpty();
        assertThat(published).containsAll(onTheWire);
    }

    // --- features ---------------------------------------------------------

    @Test
    @DisplayName("features return every seeded code with its category and both labels")
    void featuresWithLabels() throws Exception {
        JsonNode features = json(mockMvc.perform(get("/api/v1/reference/features"))
                .andExpect(status().isOk()).andReturn());

        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vehicle_feature WHERE active = true", Integer.class);
        assertThat(features).hasSize(seeded);

        JsonNode airConditioning = stream(features)
                .filter(feature -> "AIR_CONDITIONING".equals(feature.get("code").asText()))
                .findFirst().orElseThrow();
        assertThat(airConditioning.get("category").asText()).isEqualTo("COMFORT");
        assertThat(airConditioning.get("labels").get("fr-FR").asText()).isEqualTo("Climatisation");
        assertThat(airConditioning.get("labels").get("en").asText()).isEqualTo("Air conditioning");
    }

    @Test
    @DisplayName("features are ordered by category then display order, so a client can render as-is")
    void featuresAreOrdered() throws Exception {
        JsonNode features = json(mockMvc.perform(get("/api/v1/reference/features")).andReturn());

        List<String> categories = stream(features)
                .map(feature -> feature.get("category").asText()).toList();
        assertThat(categories).isSorted();
    }

    @Test
    @DisplayName("the feature labels are not reachable from any listing response")
    void labelsStayOutOfTheListingContract() throws Exception {
        // The language audit's guarantee, asserted rather than asserted-in-prose: a listing
        // carries feature codes, and the translated labels live only on this endpoint.
        JsonNode features = json(mockMvc.perform(get("/api/v1/reference/features")).andReturn());
        assertThat(features.get(0).has("labels")).isTrue();
        assertThat(features.get(0).has("label")).isFalse();
    }

    // --- catalogue --------------------------------------------------------

    @Test
    @DisplayName("makes list the active catalogue, popular first")
    void makesListTheCatalogue() throws Exception {
        fixtures.ensureCatalogue();

        JsonNode makes = json(mockMvc.perform(get("/api/v1/reference/makes"))
                .andExpect(status().isOk()).andReturn());

        assertThat(stream(makes).map(make -> make.get("name").asText()))
                .contains("BMW", "Peugeot");
        assertThat(makes.get(0).has("id")).isTrue();
        assertThat(makes.get(0).has("slug")).isTrue();
    }

    @Test
    @DisplayName("models are listed under their make")
    void modelsUnderTheirMake() throws Exception {
        ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();

        mockMvc.perform(get("/api/v1/reference/makes/" + catalogue.bmw().getId() + "/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '3 Series')]").exists());
    }

    @Test
    @DisplayName("an unknown make is 404, not an empty list")
    void unknownMakeIs404() throws Exception {
        mockMvc.perform(get("/api/v1/reference/makes/" + java.util.UUID.randomUUID() + "/models"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- countries --------------------------------------------------------

    @Test
    @DisplayName("countries return only enabled markets, with the currency scale")
    void countriesAreEnabledMarketsOnly() throws Exception {
        JsonNode countries = json(mockMvc.perform(get("/api/v1/reference/countries"))
                .andExpect(status().isOk()).andReturn());

        List<String> codes = stream(countries).map(country -> country.get("code").asText()).toList();
        assertThat(codes).containsExactly("FR");

        JsonNode france = countries.get(0);
        assertThat(france.get("currencyCode").asText()).isEqualTo("EUR");
        // Without this a client renders 1899000 minor units as nineteen thousand euros.
        assertThat(france.get("currencyMinorUnit").asInt()).isEqualTo(2);
        assertThat(france.get("defaultLocale").asText()).isEqualTo("fr-FR");
        assertThat(france.get("distanceUnit").asText()).isEqualTo("KM");
    }

    @Test
    @DisplayName("the currency symbol is still not exposed")
    void noCurrencySymbol() throws Exception {
        JsonNode countries = json(mockMvc.perform(get("/api/v1/reference/countries")).andReturn());
        // Symbol placement is a locale rule, not a currency property; Intl.NumberFormat gets it
        // right and a stored symbol does not. Returning one would invite the wrong rendering.
        assertThat(countries.get(0).has("currencySymbol")).isFalse();
        assertThat(countries.get(0).has("symbol")).isFalse();
    }

    // --- helpers ----------------------------------------------------------

    private static List<String> names(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes.stream();
    }

    /** The request and response types that actually cross the wire. */
    private static List<Class<?>> wireTypes() {
        return List.of(
                com.autox.listing.dto.CreateListingRequest.class,
                com.autox.listing.dto.UpdateListingRequest.class,
                com.autox.listing.dto.ListingResponse.class,
                com.autox.listing.dto.ListingSummary.class,
                com.autox.listing.dto.ImageResponse.class,
                com.autox.listing.dto.ImageUploadTicket.class,
                com.autox.search.ListingSearchCriteria.class,
                com.autox.search.dto.PublicListingDetail.class,
                com.autox.search.dto.PublicListingSummary.class,
                com.autox.user.dto.UserProfileResponse.class,
                com.autox.user.dto.UpdateProfileRequest.class,
                com.autox.user.dto.UserSummary.class,
                com.autox.auth.dto.RegisterRequest.class,
                com.autox.auth.dto.AuthResponse.class);
    }

    /** Collects enum types declared under {@code com.autox}, one level into nested records. */
    private static void collectEnums(Class<?> type, Set<String> found) {
        for (java.lang.reflect.RecordComponent component : componentsOf(type)) {
            Class<?> componentType = component.getType();
            if (List.class.isAssignableFrom(componentType)
                    && component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
                componentType = element;
            }
            if (componentType.isEnum() && componentType.getName().startsWith("com.autox")) {
                found.add(componentType.getSimpleName());
            } else if (componentType.isRecord() && componentType.getName().startsWith("com.autox")) {
                collectEnums(componentType, found);
            }
        }
    }

    private static java.lang.reflect.RecordComponent[] componentsOf(Class<?> type) {
        return type.isRecord() ? type.getRecordComponents() : new java.lang.reflect.RecordComponent[0];
    }
}
