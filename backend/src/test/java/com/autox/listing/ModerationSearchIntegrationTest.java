package com.autox.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Finding a listing on the moderation page: the search, each filter, the orders, and that all of
 * it is an administrator's only.
 *
 * <p>The listings are published through the API and then given the details a test is about
 * directly in the table: a title, a catalogue make, damage, a lease, a price, a date. The search
 * reads the table, so that is what it has to be tested against.
 */
class ModerationSearchIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_LISTINGS = "/api/v1/admin/listings";

    @Autowired private ListingTestFixtures fixtures;

    private JsonNode admin;

    @BeforeEach
    void signInAnAdministrator() throws Exception {
        String email = uniqueEmail("moderator");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        admin = json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));
    }

    // --- who --------------------------------------------------------------

    @Test
    @DisplayName("searching and filtering are closed to anyone but an administrator, and say nothing")
    void onlyAdministrators() throws Exception {
        JsonNode seller = seller("Hélène Dupré", UserRole.PARTICULIER, "helene");
        JsonNode dealer = seller("Garage Bellevue", UserRole.PROFESSIONNEL, "bellevue");
        listing(seller, "Peugeot 308 GT");

        MockHttpServletRequestBuilder search = get(ADMIN_LISTINGS)
                .param("view", "LIVE").param("q", "helene").param("sellerRole", "PARTICULIER")
                .param("priceMin", "0").param("sort", "PRICE_DESC");

        mockMvc.perform(search).andExpect(status().isUnauthorized());
        String forSeller = mockMvc.perform(search.header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(forSeller).doesNotContain("Peugeot").doesNotContain("helene");
        mockMvc.perform(get(ADMIN_LISTINGS).param("q", "helene").header(HttpHeaders.AUTHORIZATION, bearer(dealer)))
                .andExpect(status().isForbidden());
    }

    // --- the search -------------------------------------------------------

    @Nested
    @DisplayName("the search")
    class Search {

        @ParameterizedTest(name = "\"{0}\"")
        @CsvSource({"golf", "GOLF", "volkswagen", "gti", "performance", "golf gti", "gti golf", "Golf  GTI"})
        @DisplayName("finds a word in the title, the make, the model or the version, whatever the case and order")
        void listingWords(String query) throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "words");
            String golf = listing(seller, "Belle compacte");
            vehicle(golf, "Volkswagen", "Golf", "GTI Performance");
            listing(seller, "Renault Clio");

            assertThat(ids(search("LIVE", Map.of("q", query)))).containsExactly(golf);
        }

        @ParameterizedTest(name = "\"{0}\" finds \"{1}\"")
        @CsvSource({
                "citroen, Citroën C3",
                "CITROËN, Citroën C3",
                "citroën, Citroen C3",
                "skoda, Škoda Octavia",
                "oeuvre, Chef-d’œuvre",
        })
        @DisplayName("ignores accents, on either side, as the search bar's suggestions do")
        void accents(String query, String title) throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "accents");
            String found = listing(seller, title);
            listing(seller, "Renault Clio");

            assertThat(ids(search("LIVE", Map.of("q", query)))).containsExactly(found);
        }

        @Test
        @DisplayName("finds a catalogue make and model, which are not in the listing's own columns")
        void catalogue() throws Exception {
            ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "catalogue");
            String peugeot = listing(seller, "Belle citadine");
            jdbcTemplate.update("""
                    UPDATE listing SET make_id = ?, model_id = ?, make_text = NULL, model_text = NULL,
                                       version_text = '1.2 PureTech' WHERE id = ?::uuid""",
                    catalogue.peugeot().getId(), catalogue.twoOhEight().getId(), peugeot);
            listing(seller, "Renault Clio");

            assertThat(ids(search("LIVE", Map.of("q", "peugeot")))).containsExactly(peugeot);
            assertThat(ids(search("LIVE", Map.of("q", "208")))).containsExactly(peugeot);
            assertThat(ids(search("LIVE", Map.of("q", "Peugeot 208 puretech")))).containsExactly(peugeot);
        }

        @Test
        @DisplayName("finds the seller by display name, accents or not, and by any part of the e-mail")
        void sellerNameAndEmail() throws Exception {
            JsonNode helene = seller("Hélène Dupré", UserRole.PARTICULIER, "h.dupre");
            JsonNode other = seller("Marc Petit", UserRole.PARTICULIER, "marc");
            String hers = listing(helene, "Peugeot 208");
            listing(other, "Peugeot 208");

            assertThat(ids(search("LIVE", Map.of("q", "helene dupre")))).containsExactly(hers);
            assertThat(ids(search("LIVE", Map.of("q", "DUPRÉ")))).containsExactly(hers);
            String email = helene.get("user").get("email").asText();
            assertThat(ids(search("LIVE", Map.of("q", email)))).containsExactly(hers);
            assertThat(ids(search("LIVE", Map.of("q", "h.dupre")))).containsExactly(hers);
            assertThat(ids(search("LIVE", Map.of("q", email.toUpperCase())))).containsExactly(hers);
            // One word on the listing, one on its seller.
            assertThat(ids(search("LIVE", Map.of("q", "208 helene")))).containsExactly(hers);
        }

        @Test
        @DisplayName("needs every word: one that matches nothing empties the list")
        void everyWord() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "every");
            listing(seller, "Peugeot 308");

            JsonNode page = search("LIVE", Map.of("q", "peugeot ferrari"));
            assertThat(ids(page)).isEmpty();
            assertThat(page.get("totalElements").asInt()).isZero();
        }

        @Test
        @DisplayName("takes % and _ for themselves, not as wildcards")
        void wildcards() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "wild");
            listing(seller, "Peugeot 308");
            String percent = listing(seller, "Remise 10% Peugeot");

            assertThat(ids(search("LIVE", Map.of("q", "%")))).containsExactly(percent);
            assertThat(ids(search("LIVE", Map.of("q", "_")))).isEmpty();
        }

        @Test
        @DisplayName("searches within the list it is on")
        void withinTheView() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "view");
            String queued = listing(seller, "Peugeot 308");
            String approved = listing(seller, "Peugeot 3008");
            jdbcTemplate.update("UPDATE listing SET moderation_status = 'APPROVED' WHERE id = ?::uuid", approved);
            String removed = listing(seller, "Peugeot 5008");
            jdbcTemplate.update("UPDATE listing SET status = 'REMOVED', moderation_status = 'REJECTED' WHERE id = ?::uuid",
                    removed);

            assertThat(ids(search("PENDING", Map.of("q", "peugeot")))).containsExactly(queued);
            assertThat(ids(search("LIVE", Map.of("q", "peugeot")))).containsExactlyInAnyOrder(queued, approved);
            assertThat(ids(search("REMOVED", Map.of("q", "peugeot")))).containsExactly(removed);
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', value = {
                "listing l | ix_listing_moderation_text  | l.deleted_at IS NULL AND",
                "app_user s | ix_app_user_moderation_text | ",
        })
        @DisplayName("is answered by its trigram indexes, whose expressions the query repeats exactly")
        void indexed(String table, String index, String also) throws Exception {
            String text = table.startsWith("listing") ? ModerationSearchRepository.LISTING_TEXT
                    : ModerationSearchRepository.SELLER_TEXT;
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "plan");
            String model = listing(seller, "Annonce");
            String plan = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (java.sql.Statement statement = connection.createStatement()) {
                    // Enough rows for an index to be worth it, copied from a real one and rolled
                    // back: on a near-empty table any plan will do, and none would prove anything.
                    // The columns that can be written: a generated one (crit_air, V30) cannot be copied in.
                    String columns = jdbcTemplate.queryForObject("""
                            SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
                              FROM information_schema.columns
                             WHERE table_schema = 'public' AND table_name = 'listing' AND is_generated = 'NEVER'""",
                            String.class);
                    statement.execute("""
                            INSERT INTO listing (%1$s) SELECT %1$s FROM (
                                SELECT (jsonb_populate_record(l, jsonb_build_object(
                                       'id', md5('l' || n)::uuid, 'title', 'Annonce ' || n))).*
                                  FROM listing l, generate_series(1, 3000) n WHERE l.id = '%2$s') copy""".formatted(
                            columns, model));
                    statement.execute("""
                            INSERT INTO app_user SELECT (jsonb_populate_record(u, jsonb_build_object(
                                       'id', md5('u' || n)::uuid, 'email', 'copy-' || n || '@autox.test'))).*
                              FROM app_user u, generate_series(1, 3000) n WHERE u.id = '%s'""".formatted(
                            seller.get("user").get("id").asText()));
                    statement.execute("ANALYZE listing");
                    statement.execute("ANALYZE app_user");
                    statement.execute("SET LOCAL enable_seqscan = off");
                    try (java.sql.ResultSet rows = statement.executeQuery("EXPLAIN (FORMAT JSON) SELECT 1 FROM " + table
                            + " WHERE " + (also == null ? "" : also) + " " + text
                            + " LIKE '%' || public.catalogue_search_key('golf') || '%' ESCAPE '\\'")) {
                        rows.next();
                        return rows.getString(1);
                    }
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                    // ANALYZE's row counts are written outside the transaction: left as they are,
                    // the rolled-back copies would mislead the planner in every test after this one.
                    try (java.sql.Statement statement = connection.createStatement()) {
                        statement.execute("ANALYZE listing");
                        statement.execute("ANALYZE app_user");
                    }
                }
            });
            assertThat(plan).contains("\"Index Name\": \"" + index + "\"");
        }

        @Test
        @DisplayName("is refused past 100 characters")
        void tooLong() throws Exception {
            mockMvc.perform(get(ADMIN_LISTINGS).param("q", "a".repeat(101))
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // --- the filters ------------------------------------------------------

    @Nested
    @DisplayName("the filters")
    class Filters {

        @Test
        @DisplayName("make: the catalogue's, or the same name typed, whatever the case and accents")
        void make() throws Exception {
            ListingTestFixtures.Catalogue catalogue = fixtures.ensureCatalogue();
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "make");
            String fromCatalogue = listing(seller, "Citadine");
            jdbcTemplate.update("UPDATE listing SET make_id = ?, model_id = ?, make_text = NULL, model_text = NULL"
                    + " WHERE id = ?::uuid", catalogue.peugeot().getId(), catalogue.twoOhEight().getId(), fromCatalogue);
            String typed = listing(seller, "Citadine");
            vehicle(typed, "PEUGEOT", "106", null);
            String bmw = listing(seller, "Berline");
            vehicle(bmw, "BMW", "Série 3", "320d");

            assertThat(ids(search("LIVE", Map.of("make", "Peugeot")))).containsExactlyInAnyOrder(fromCatalogue, typed);
            assertThat(ids(search("LIVE", Map.of("make", "bmw")))).containsExactly(bmw);
            // A filter on a name, not a word in it.
            assertThat(ids(search("LIVE", Map.of("make", "Peu")))).isEmpty();
        }

        @Test
        @DisplayName("seller type")
        void sellerRole() throws Exception {
            String privately = listing(seller("Particulier", UserRole.PARTICULIER, "private"), "Clio");
            String dealer = listing(seller("Garage", UserRole.PROFESSIONNEL, "dealer"), "Clio");

            assertThat(ids(search("LIVE", Map.of("sellerRole", "PARTICULIER")))).containsExactly(privately);
            assertThat(ids(search("LIVE", Map.of("sellerRole", "PROFESSIONNEL")))).containsExactly(dealer);
        }

        @Test
        @DisplayName("damaged, either way")
        void damaged() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "damage");
            String damaged = listing(seller, "Clio accidentée");
            jdbcTemplate.update("""
                    UPDATE listing SET damaged = TRUE, damage_types = ARRAY['BODYWORK'],
                                       damage_description = 'Choc avant.' WHERE id = ?::uuid""", damaged);
            String sound = listing(seller, "Clio");

            assertThat(ids(search("LIVE", Map.of("damaged", "true")))).containsExactly(damaged);
            assertThat(ids(search("LIVE", Map.of("damaged", "false")))).containsExactly(sound);
        }

        @Test
        @DisplayName("sale or leasing")
        void offer() throws Exception {
            JsonNode dealer = seller("Garage", UserRole.PROFESSIONNEL, "lease");
            String lease = listing(dealer, "Peugeot 208 en LLD");
            jdbcTemplate.update("""
                    UPDATE listing SET offer_type = 'LEASING', leasing_type = 'LLD', leasing_monthly = 30000,
                                       leasing_first_payment = 100000, leasing_duration_months = 36,
                                       leasing_mileage_per_year = 10000, leasing_total_cost = 1150000
                     WHERE id = ?::uuid""", lease);
            String sale = listing(dealer, "Peugeot 208");

            assertThat(ids(search("LIVE", Map.of("offer", "LEASING")))).containsExactly(lease);
            assertThat(ids(search("LIVE", Map.of("offer", "SALE")))).containsExactly(sale);
        }

        @Test
        @DisplayName("real or demo, and every listing says which it is")
        void demo() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "demo");
            String real = listing(seller, "Clio");
            String seeded = listing(seller, "Clio");

            // Without a ledger nothing is demo.
            assertThat(ids(search("LIVE", Map.of("demo", "true")))).isEmpty();
            assertThat(ids(search("LIVE", Map.of("demo", "false")))).containsExactlyInAnyOrder(real, seeded);

            jdbcTemplate.execute("""
                    CREATE TABLE demo_seed_ledger (entity_type VARCHAR(20) NOT NULL, entity_id UUID NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (entity_type, entity_id))""");
            try {
                jdbcTemplate.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES ('LISTING', ?::uuid)",
                        seeded);

                assertThat(ids(search("LIVE", Map.of("demo", "true")))).containsExactly(seeded);
                assertThat(ids(search("LIVE", Map.of("demo", "false")))).containsExactly(real);
                JsonNode all = search("LIVE", Map.of());
                for (JsonNode item : all.get("items")) {
                    assertThat(item.get("demo").asBoolean())
                            .isEqualTo(item.get("listing").get("id").asText().equals(seeded));
                }
                mockMvc.perform(get(ADMIN_LISTINGS + "/" + seeded).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                        .andExpect(jsonPath("$.demo").value(true));
            } finally {
                jdbcTemplate.execute("DROP TABLE demo_seed_ledger");
            }
        }

        @Test
        @DisplayName("a price range, bounds included, in minor units")
        void price() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "price");
            String cheap = priced(listing(seller, "A"), 500_000);
            String middle = priced(listing(seller, "B"), 1_000_000);
            String dear = priced(listing(seller, "C"), 2_000_000);

            assertThat(ids(search("LIVE", Map.of("priceMin", "1000000")))).containsExactlyInAnyOrder(middle, dear);
            assertThat(ids(search("LIVE", Map.of("priceMax", "1000000")))).containsExactlyInAnyOrder(cheap, middle);
            assertThat(ids(search("LIVE", Map.of("priceMin", "600000", "priceMax", "1999999")))).containsExactly(middle);

            mockMvc.perform(get(ADMIN_LISTINGS).param("priceMin", "2").param("priceMax", "1")
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mockMvc.perform(get(ADMIN_LISTINGS).param("priceMin", "-1").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("together, with the search, and counted")
        void combined() throws Exception {
            JsonNode dealer = seller("Garage Bellevue", UserRole.PROFESSIONNEL, "combined");
            JsonNode person = seller("Marc Petit", UserRole.PARTICULIER, "person");
            String wanted = priced(listing(dealer, "Peugeot 308"), 1_500_000);
            priced(listing(dealer, "Peugeot 308"), 3_000_000);
            priced(listing(person, "Peugeot 308"), 1_500_000);
            priced(listing(dealer, "Renault Clio"), 1_500_000);

            JsonNode page = search("LIVE", Map.of("q", "308 bellevue", "sellerRole", "PROFESSIONNEL",
                    "priceMax", "2000000", "damaged", "false", "offer", "SALE"));
            assertThat(ids(page)).containsExactly(wanted);
            assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("a value that is not one of the choices is refused")
        void unknownValues() throws Exception {
            for (String[] bad : List.of(new String[] {"sellerRole", "DEALER"}, new String[] {"offer", "RENT"},
                    new String[] {"sort", "CHEAPEST"}, new String[] {"damaged", "maybe"})) {
                mockMvc.perform(get(ADMIN_LISTINGS).param(bad[0], bad[1]).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    // --- the orders -------------------------------------------------------

    @Nested
    @DisplayName("the orders")
    class Orders {

        @Test
        @DisplayName("the queue is oldest first unless asked otherwise, by when the listing was created")
        void queue() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "queue");
            String middle = dated(listing(seller, "B"), 2);
            String oldest = dated(listing(seller, "A"), 3);
            String newest = dated(listing(seller, "C"), 1);

            assertThat(ids(search("PENDING", Map.of()))).containsExactly(oldest, middle, newest);
            assertThat(ids(search("PENDING", Map.of("sort", "OLDEST")))).containsExactly(oldest, middle, newest);
            assertThat(ids(search("PENDING", Map.of("sort", "NEWEST")))).containsExactly(newest, middle, oldest);
        }

        @Test
        @DisplayName("what is online is newest first unless asked otherwise, by when it went online")
        void live() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "live");
            String middle = dated(listing(seller, "B"), 2);
            String oldest = dated(listing(seller, "A"), 3);
            String newest = dated(listing(seller, "C"), 1);

            assertThat(ids(search("LIVE", Map.of()))).containsExactly(newest, middle, oldest);
            assertThat(ids(search("LIVE", Map.of("sort", "OLDEST")))).containsExactly(oldest, middle, newest);
        }

        @Test
        @DisplayName("what was taken down is newest first, by when it was taken down")
        void removed() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "removed");
            String first = listing(seller, "A");
            String second = listing(seller, "B");
            jdbcTemplate.update("UPDATE listing SET status = 'REMOVED', moderation_status = 'REJECTED', updated_at = ?"
                    + " WHERE id = ?::uuid", daysAgo(5), first);
            jdbcTemplate.update("UPDATE listing SET status = 'REMOVED', moderation_status = 'REJECTED', updated_at = ?"
                    + " WHERE id = ?::uuid", daysAgo(1), second);

            assertThat(ids(search("REMOVED", Map.of()))).containsExactly(second, first);
            assertThat(ids(search("REMOVED", Map.of("sort", "OLDEST")))).containsExactly(first, second);
        }

        @Test
        @DisplayName("by price, either way, on any list")
        void price() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "by-price");
            String middle = priced(listing(seller, "B"), 1_000_000);
            String dear = priced(listing(seller, "C"), 2_000_000);
            String cheap = priced(listing(seller, "A"), 500_000);

            assertThat(ids(search("LIVE", Map.of("sort", "PRICE_ASC")))).containsExactly(cheap, middle, dear);
            assertThat(ids(search("LIVE", Map.of("sort", "PRICE_DESC")))).containsExactly(dear, middle, cheap);
            assertThat(ids(search("PENDING", Map.of("sort", "PRICE_DESC")))).containsExactly(dear, middle, cheap);
        }

        @Test
        @DisplayName("pages through a filtered list in order, with its count")
        void pages() throws Exception {
            JsonNode seller = seller("Vendeur", UserRole.PROFESSIONNEL, "paged");
            List<String> byPrice = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                byPrice.add(priced(listing(seller, "Peugeot " + i), i * 100_000L));
            }
            listing(seller, "Renault");

            JsonNode first = search("LIVE", Map.of("q", "peugeot", "sort", "PRICE_ASC", "size", "2"));
            JsonNode last = search("LIVE", Map.of("q", "peugeot", "sort", "PRICE_ASC", "size", "2", "page", "2"));
            assertThat(ids(first)).containsExactly(byPrice.get(0), byPrice.get(1));
            assertThat(first.get("totalElements").asInt()).isEqualTo(5);
            assertThat(first.get("totalPages").asInt()).isEqualTo(3);
            assertThat(ids(last)).containsExactly(byPrice.get(4));
            assertThat(last.get("hasNext").asBoolean()).isFalse();
        }
    }

    // --- helpers ----------------------------------------------------------

    /**
     * A signed-in seller with this display name and an e-mail starting with {@code emailPrefix}. The
     * test profile caps a private seller at two listings online, so where the kind of seller does
     * not matter ("Vendeur") it is a professional.
     */
    private JsonNode seller(String name, UserRole role, String emailPrefix) throws Exception {
        // Not uniqueEmail: the search reads e-mails, and a random id in one ("…208…") would match
        // a test's words. Every test starts from empty tables, so the prefix alone is unique.
        String email = emailPrefix + "@seller.autox.test";
        JsonNode account = register(email, role);
        jdbcTemplate.update("UPDATE app_user SET display_name = ? WHERE email = ?", name, email);
        return account;
    }

    /** A published listing with this title; its vehicle is the fixture's free-text BMW until changed. */
    private String listing(JsonNode seller, String title) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>(ListingTestFixtures.freeTextListingPayload());
        String id = objectMapper.readTree(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        // The fixture names a BMW 3 Series: the title is set alone, so a test's own words are the
        // only ones it can match.
        jdbcTemplate.update("UPDATE listing SET title = ?, make_text = 'Marque', model_text = 'Modèle',"
                + " version_text = NULL WHERE id = ?::uuid", title, id);
        return id;
    }

    private void vehicle(String id, String make, String model, String version) {
        jdbcTemplate.update("UPDATE listing SET make_text = ?, model_text = ?, version_text = ? WHERE id = ?::uuid",
                make, model, version, id);
    }

    private String priced(String id, long amount) {
        jdbcTemplate.update("UPDATE listing SET price_amount = ? WHERE id = ?::uuid", amount, id);
        return id;
    }

    /** Created, put online and last changed this many days ago. */
    private String dated(String id, int days) {
        jdbcTemplate.update("UPDATE listing SET created_at = ?, published_at = ?, updated_at = ? WHERE id = ?::uuid",
                daysAgo(days), daysAgo(days), daysAgo(days), id);
        return id;
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private JsonNode search(String view, Map<String, String> parameters) throws Exception {
        MockHttpServletRequestBuilder request = get(ADMIN_LISTINGS).param("view", view)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin));
        parameters.forEach(request::param);
        return json(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("listing").get("id").asText()));
        return ids;
    }
}
