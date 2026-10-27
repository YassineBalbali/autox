package com.autox.catalog.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

/**
 * The search bar's suggestions: prefix matching on makes and on "make model", without
 * regard to case or accents, and never an entry with nothing online behind it.
 */
class CatalogueSuggestionIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/catalogue/suggestions";

    private final List<UUID> makes = new ArrayList<>();
    private final List<UUID> models = new ArrayList<>();
    private UUID seller;

    @BeforeEach
    void seller() {
        seller = createUser(uniqueEmail("seller"), UserRole.PROFESSIONNEL, UserStatus.ACTIVE).getId();
    }

    @AfterEach
    void removeCatalogue() {
        // The harness empties the listing tables between tests, not the catalogue.
        jdbcTemplate.update("DELETE FROM listing");
        models.forEach(id -> jdbcTemplate.update("DELETE FROM vehicle_model WHERE id = ?", id));
        makes.forEach(id -> jdbcTemplate.update("DELETE FROM vehicle_make WHERE id = ?", id));
    }

    // ---- matching ---------------------------------------------------------------------

    @Test
    @DisplayName("\"audi a\" suggests the Audi models starting with A, most listed first")
    void makeThenModelPrefix() throws Exception {
        UUID audi = make("audi", "Audi");
        listed(audi, model(audi, "a1", "A1"), 1);
        listed(audi, model(audi, "a3", "A3"), 4);
        listed(audi, model(audi, "a4", "A4"), 2);
        listed(audi, model(audi, "q5", "Q5"), 3);

        assertThat(labels("audi a")).containsExactly("Audi A3", "Audi A4", "Audi A1");
        // Each with what the search URL and the rest of the API need.
        mockMvc.perform(get(PATH).param("q", "audi a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MODEL"))
                .andExpect(jsonPath("$[0].makeId").value(audi.toString()))
                .andExpect(jsonPath("$[0].makeSlug").value("audi"))
                .andExpect(jsonPath("$[0].makeName").value("Audi"))
                .andExpect(jsonPath("$[0].modelId").isNotEmpty())
                .andExpect(jsonPath("$[0].modelSlug").value("a3"))
                .andExpect(jsonPath("$[0].modelName").value("A3"))
                .andExpect(jsonPath("$[0].label").value("Audi A3"))
                .andExpect(jsonPath("$[0].listingCount").value(4));
    }

    @Test
    @DisplayName("a model is found by its own name too: \"a3\" finds the Audi A3")
    void modelByItsOwnName() throws Exception {
        UUID audi = make("audi", "Audi");
        listed(audi, model(audi, "a3", "A3"), 1);
        listed(audi, model(audi, "a4", "A4"), 1);

        assertThat(labels("a3")).containsExactly("Audi A3");
        assertThat(labels("A3")).containsExactly("Audi A3");
    }

    @Test
    @DisplayName("a make is found by the start of its name, and stands alone")
    void makeByPrefix() throws Exception {
        UUID peugeot = make("peugeot", "Peugeot");
        listed(peugeot, model(peugeot, "208", "208"), 2);

        JsonNode found = suggest("peu");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("type").asText()).isEqualTo("MAKE");
        assertThat(found.get(0).get("label").asText()).isEqualTo("Peugeot");
        assertThat(found.get(0).get("listingCount").asLong()).isEqualTo(2);
        assertThat(found.get(0).has("modelId")).isFalse();
        // Not the middle of a name.
        assertThat(labels("geot")).isEmpty();
    }

    @Test
    @DisplayName("case and accents are ignored, on either side")
    void caseAndAccentsAreIgnored() throws Exception {
        UUID citroen = make("citroen", "Citroën");
        listed(citroen, model(citroen, "c5-aircross", "C5 Aircross"), 1);
        UUID skoda = make("skoda", "Škoda");
        listed(skoda, model(skoda, "octavia", "Octavia"), 1);
        UUID renault = make("renault", "Renault");
        listed(renault, model(renault, "scenic", "Scénic"), 1);

        assertThat(labels("citroen")).containsExactly("Citroën");
        assertThat(labels("CITROËN")).containsExactly("Citroën");
        assertThat(labels("citroen c5")).containsExactly("Citroën C5 Aircross");
        assertThat(labels("skoda")).containsExactly("Škoda");
        assertThat(labels("ŠKO")).containsExactly("Škoda");
        assertThat(labels("renault sce")).containsExactly("Renault Scénic");
        assertThat(labels("scé")).containsExactly("Renault Scénic");
        assertThat(labels("SCENIC")).containsExactly("Renault Scénic");
        // Spaces as typed: outer ones dropped, inner runs counted as one.
        assertThat(labels("  renault   scé ")).containsExactly("Renault Scénic");
    }

    @Test
    @DisplayName("a typed % or _ is a character, not a wildcard")
    void noWildcards() throws Exception {
        UUID audi = make("audi", "Audi");
        listed(audi, model(audi, "a3", "A3"), 1);

        assertThat(labels("a%")).isEmpty();
        assertThat(labels("a_")).isEmpty();
        assertThat(labels("%%")).isEmpty();
    }

    // ---- limits -----------------------------------------------------------------------

    @Test
    @DisplayName("fewer than two characters is refused, outer spaces not counting; more than sixty too")
    void twoCharacterFloor() throws Exception {
        UUID audi = make("audi", "Audi");
        listed(audi, model(audi, "a3", "A3"), 1);

        for (String tooShort : List.of("", "a", " a ", "   ")) {
            mockMvc.perform(get(PATH).param("q", tooShort))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(PATH)).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("q", "a".repeat(61))).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("q", "au")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("at most eight, the most listed kept")
    void eightAtMost() throws Exception {
        UUID audi = make("audi", "Audi");
        String[] names = {"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A4 Allroad", "A6 Allroad"};
        for (int index = 0; index < names.length; index++) {
            listed(audi, model(audi, names[index].toLowerCase().replace(' ', '-'), names[index]), index + 1);
        }

        List<String> found = labels("audi a");
        assertThat(found).hasSize(8);
        // The two least listed, A1 and A2, are the ones left out.
        assertThat(found).doesNotContain("Audi A1", "Audi A2").startsWith("Audi A6 Allroad", "Audi A4 Allroad");
    }

    @Test
    @DisplayName("makes come first, then the most listed")
    void makesFirstThenByCount() throws Exception {
        UUID cadillac = make("cadillac", "Cadillac");
        listed(cadillac, model(cadillac, "escalade", "Escalade"), 1);
        UUID renault = make("renault", "Renault");
        listed(renault, model(renault, "captur", "Captur"), 5);
        UUID volkswagen = make("volkswagen", "Volkswagen");
        listed(volkswagen, model(volkswagen, "caddy", "Caddy"), 2);

        assertThat(labels("ca")).containsExactly("Cadillac", "Renault Captur", "Volkswagen Caddy");
    }

    // ---- no dead ends -----------------------------------------------------------------

    @Test
    @DisplayName("an entry with nothing online is never suggested, nor counted from listings that are not")
    void noDeadEnds() throws Exception {
        UUID audi = make("audi", "Audi");
        listed(audi, model(audi, "a3", "A3"), 2);
        model(audi, "a1", "A1");                                     // no listing at all
        UUID a4 = model(audi, "a4", "A4");
        listing(audi, a4, "DRAFT", "NOT_REVIEWED", "now() + interval '60 days'");
        listing(audi, a4, "ACTIVE", "REJECTED", "now() + interval '60 days'");
        listing(audi, a4, "ACTIVE", "NOT_REVIEWED", "now() - interval '1 day'");   // expired
        UUID a5 = model(audi, "a5", "A5");
        listing(audi, a5, "ACTIVE", "NOT_REVIEWED", "now() + interval '60 days'");
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() WHERE model_id = ?", a5);
        // A make whose only listing is not online.
        UUID alpine = make("alpine", "Alpine");
        listing(alpine, model(alpine, "a110", "A110"), "SOLD", "NOT_REVIEWED", "now() + interval '60 days'");

        assertThat(labels("audi a")).containsExactly("Audi A3");
        assertThat(suggest("audi").get(0).get("listingCount").asLong()).isEqualTo(2);
        assertThat(labels("alp")).isEmpty();
        assertThat(labels("a1")).isEmpty();
    }

    @Test
    @DisplayName("an inactive make or model is not suggested")
    void inactiveIsHidden() throws Exception {
        UUID audi = make("audi", "Audi");
        UUID a3 = model(audi, "a3", "A3");
        listed(audi, a3, 1);
        jdbcTemplate.update("UPDATE vehicle_model SET active = FALSE WHERE id = ?", a3);
        assertThat(labels("audi a")).isEmpty();

        jdbcTemplate.update("UPDATE vehicle_model SET active = TRUE WHERE id = ?", a3);
        jdbcTemplate.update("UPDATE vehicle_make SET active = FALSE WHERE id = ?", audi);
        assertThat(labels("a3")).isEmpty();
        assertThat(labels("aud")).isEmpty();
    }

    // ---- contract ---------------------------------------------------------------------

    @Test
    @DisplayName("public, without a token, and cached for a minute")
    void publicAndBrieflyCached() throws Exception {
        mockMvc.perform(get(PATH).param("q", "zz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    /** The V16 indexes, each with the column expression its Index Cond must test. */
    private static final List<String> SEARCH_INDEXES = List.of(
            "ix_vehicle_make_search_key", "ix_vehicle_model_search_key", "ix_vehicle_model_make_search_key");

    @Test
    @DisplayName("each V16 index can answer the prefix test of this query, whatever the data and statistics")
    void eachIndexServesThePrefixTest() {
        // What matters is that each index CAN answer the prefix range for this query shape:
        // that its expression is the query's, character for character, and that the range is
        // a condition the index accepts. Which index the planner then PREFERS is its call, made
        // from row counts and statistics. On a small, never-analysed catalogue it is right to
        // pick a sequential scan, and with three indexes on overlapping expressions any of them
        // may win, so asserting a preference failed on CI and passed locally on the same code.
        //
        // So the choice is forced rather than fed: in a transaction that is always rolled back,
        // the other two indexes are dropped and sequential scans priced out, which leaves the
        // index under test as the only cheap way in. Loading rows and running ANALYZE instead
        // would still leave the choice between the remaining indexes to the planner. And the
        // check is on the Index Cond, not the index's name: the indexes are partial (WHERE
        // active), so the planner may scan one whole and filter, which a name alone would
        // pass. Run twice, on whatever rows the shared database holds and on empty tables.
        for (boolean emptied : new boolean[] {false, true}) {
            for (String index : SEARCH_INDEXES) {
                List<JsonNode> scans = indexScansWith(index, emptied);
                assertThat(scans).as("%s (emptied tables: %s): an index node using it", index, emptied).isNotEmpty();
                assertThat(scans).as("%s (emptied tables: %s): the prefix range in its Index Cond", index, emptied)
                        .anySatisfy(scan -> assertThat(scan.path("Index Cond").asText())
                                .contains("catalogue_search_key(").contains("~>=~").contains("~<~"));
                if (index.equals("ix_vehicle_model_make_search_key")) {
                    // The "audi a" branch: the make's models, then the rest of the prefix.
                    assertThat(scans).anySatisfy(scan -> assertThat(scan.path("Index Cond").asText()).contains("make_id ="));
                }
            }
        }
    }

    /**
     * The plan's index nodes using {@code index}, with the other two V16 indexes dropped and
     * sequential scans priced out, in a transaction rolled back whatever happens.
     */
    private List<JsonNode> indexScansWith(String index, boolean emptied) {
        String plan = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                for (String other : SEARCH_INDEXES) {
                    if (!other.equals(index)) {
                        statement.execute("DROP INDEX " + other);
                    }
                }
                if (emptied) {
                    statement.execute("DELETE FROM listing");
                    statement.execute("DELETE FROM vehicle_trim");
                    statement.execute("DELETE FROM vehicle_model");
                    statement.execute("DELETE FROM vehicle_make");
                }
                statement.execute("SET LOCAL enable_seqscan = off");
                try (PreparedStatement explain = connection.prepareStatement(
                        "EXPLAIN (FORMAT JSON) " + CatalogueSuggestionRepository.SQL)) {
                    explain.setString(1, "audi a");
                    explain.setInt(2, CatalogueSuggestionController.MAX_RESULTS);
                    try (ResultSet rows = explain.executeQuery()) {
                        rows.next();
                        return rows.getString(1);
                    }
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
        List<JsonNode> scans = new ArrayList<>();
        try {
            collectIndexScans(objectMapper.readTree(plan).get(0).get("Plan"), index, scans);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return scans;
    }

    private static void collectIndexScans(JsonNode node, String index, List<JsonNode> into) {
        if (index.equals(node.path("Index Name").asText())) {
            into.add(node);
        }
        node.path("Plans").forEach(child -> collectIndexScans(child, index, into));
    }

    // ---- helpers ----------------------------------------------------------------------

    private JsonNode suggest(String q) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(PATH).param("q", q))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> labels(String q) throws Exception {
        List<String> labels = new ArrayList<>();
        suggest(q).forEach(entry -> labels.add(entry.get("label").asText()));
        return labels;
    }

    private UUID make(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("DELETE FROM vehicle_model WHERE make_id IN (SELECT id FROM vehicle_make WHERE slug = ?)", slug);
        jdbcTemplate.update("DELETE FROM vehicle_make WHERE slug = ?", slug);
        jdbcTemplate.update("INSERT INTO vehicle_make (id, slug, name) VALUES (?, ?, ?)", id, slug, name);
        makes.add(id);
        return id;
    }

    private UUID model(UUID make, String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vehicle_model (id, make_id, slug, name) VALUES (?, ?, ?, ?)",
                id, make, slug, name);
        models.add(0, id);
        return id;
    }

    /** {@code count} listings online for the model. */
    private void listed(UUID make, UUID model, int count) {
        for (int index = 0; index < count; index++) {
            listing(make, model, "ACTIVE", "NOT_REVIEWED", "now() + interval '60 days'");
        }
    }

    private void listing(UUID make, UUID model, String status, String moderation, String expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code,
                                     postal_code, city, make_id, model_id,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Annonce',
                        1500000, 'EUR', 'FR', '75011', 'Paris', ?, ?,
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED',
                        now(), %s, now(), now())
                """.formatted(expiresAt), UUID.randomUUID(), seller, status, moderation, make, model);
    }
}
