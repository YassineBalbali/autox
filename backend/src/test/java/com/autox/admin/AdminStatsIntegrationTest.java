package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The admin dashboard's endpoint against a real database: who may read it, and every figure
 * against rows the test put there. Rows are inserted directly, because the point is the counting,
 * and because a date in the past cannot be made through the API.
 */
class AdminStatsIntegrationTest extends AbstractIntegrationTest {

    private static final String STATS = "/api/v1/admin/stats";
    private static final String ONLINE = "now() + interval '60 days'";

    private final List<UUID> makes = new ArrayList<>();
    private UUID seller;
    private JsonNode admin;

    @BeforeEach
    void accounts() throws Exception {
        seller = createUser(uniqueEmail("seller"), UserRole.PROFESSIONNEL, UserStatus.ACTIVE).getId();
        admin = login(UserRole.ADMIN);
    }

    @AfterEach
    void removeWhatTheHarnessKeeps() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS demo_seed_ledger");
        jdbcTemplate.update("DELETE FROM listing");
        makes.forEach(id -> {
            jdbcTemplate.update("DELETE FROM vehicle_model WHERE make_id = ?", id);
            jdbcTemplate.update("DELETE FROM vehicle_make WHERE id = ?", id);
        });
    }

    // ---- who may read it ----------------------------------------------------------------

    @Test
    @DisplayName("no session is 401, any other role is 403, an administrator is 200")
    void authorization() throws Exception {
        mockMvc.perform(get(STATS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(STATS).header(HttpHeaders.AUTHORIZATION, bearer(login(UserRole.PARTICULIER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(STATS).header(HttpHeaders.AUTHORIZATION, bearer(login(UserRole.PROFESSIONNEL))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(STATS).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                // Nothing between here and the administrator may keep a copy.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, private"));
    }

    // ---- empty --------------------------------------------------------------------------

    @Test
    @DisplayName("an empty site is zeros, thirty empty days and no makes, not an error")
    void empty() throws Exception {
        JsonNode stats = stats();

        assertThat(stats.path("demoDataPresent").asBoolean()).isFalse();
        for (String state : List.of("published", "pendingModeration", "rejected", "sold", "expired")) {
            assertSplit(stats.path("listings").path(state), 0, 0);
        }
        assertSplit(stats.path("last30Days").path("newListings"), 0, 0);
        assertSplit(stats.path("last30Days").path("messages"), 0, 0);
        assertThat(stats.path("daily")).hasSize(30);
        assertThat(stats.path("topMakes")).isEmpty();
        assertSplit(stats.path("priceVerdicts").path("noVerdict"), 0, 0);
        assertThat(stats.path("searchLast30Days").path("requests").asInt()).isZero();
    }

    // ---- listings by state --------------------------------------------------------------

    @Test
    @DisplayName("each listing state is counted, and only what a visitor can find is published")
    void listingsByState() throws Exception {
        listing("ACTIVE", "NOT_REVIEWED", ONLINE);
        listing("ACTIVE", "NOT_REVIEWED", ONLINE);
        listing("ACTIVE", "APPROVED", ONLINE);
        listing("REMOVED", "REJECTED", ONLINE);
        listing("SOLD", "APPROVED", ONLINE);
        listing("SOLD", "NOT_REVIEWED", ONLINE);
        listing("EXPIRED", "APPROVED", "now() - interval '1 day'");
        // None of these is any of the five.
        listing("DRAFT", "NOT_REVIEWED", "NULL");
        listing("PAUSED", "APPROVED", ONLINE);
        // Past its date and not yet swept: no longer online, though its status has not caught up.
        listing("ACTIVE", "APPROVED", "now() - interval '1 hour'");
        // Deleted by its seller: gone, whatever it says.
        UUID deleted = listing("ACTIVE", "APPROVED", ONLINE);
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() WHERE id = ?", deleted);

        JsonNode listings = stats().path("listings");

        assertSplit(listings.path("published"), 3, 0);
        assertSplit(listings.path("pendingModeration"), 2, 0);
        assertSplit(listings.path("rejected"), 1, 0);
        assertSplit(listings.path("sold"), 2, 0);
        assertSplit(listings.path("expired"), 1, 0);
    }

    // ---- activity ------------------------------------------------------------------------

    @Test
    @DisplayName("new listings, registrations and messages fall in the right window")
    void activityWindows() throws Exception {
        UUID recent = listingCreated("now() - interval '3 days'");
        listingCreated("now() - interval '20 days'");
        listingCreated("now() - interval '40 days'");

        userCreated("now() - interval '2 days'");
        userCreated("now() - interval '10 days'");
        userCreated("now() - interval '31 days'");

        UUID buyer = createUser(uniqueEmail("buyer"), UserRole.PARTICULIER, UserStatus.ACTIVE).getId();
        UUID conversation = conversation(recent, buyer);
        message(conversation, buyer, "now() - interval '1 day'");
        message(conversation, seller, "now() - interval '8 days'");
        message(conversation, buyer, "now() - interval '29 days'");
        message(conversation, seller, "now() - interval '35 days'");

        JsonNode stats = stats();

        // Registered just now, in this test's setup: the seller, the administrator, the buyer.
        long justNow = 3;
        assertSplit(stats.path("last7Days").path("newListings"), 1, 0);
        assertSplit(stats.path("last30Days").path("newListings"), 2, 0);
        assertSplit(stats.path("last7Days").path("registrations"), justNow + 1, 0);
        assertSplit(stats.path("last30Days").path("registrations"), justNow + 2, 0);
        assertSplit(stats.path("last7Days").path("messages"), 1, 0);
        assertSplit(stats.path("last30Days").path("messages"), 3, 0);
    }

    // ---- daily ---------------------------------------------------------------------------

    @Test
    @DisplayName("thirty days, oldest first, ending today, each listing and account on its own day")
    void dailySeries() throws Exception {
        listingCreated("now()");
        listingCreated("now()");
        listingCreated("now() - interval '5 days'");
        listingCreated("now() - interval '29 days'");
        // Outside the series.
        listingCreated("now() - interval '30 days'");
        userCreated("now() - interval '5 days'");

        JsonNode daily = stats().path("daily");

        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        assertThat(daily).hasSize(30);
        assertThat(daily.get(0).path("day").asText()).isEqualTo(today.minusDays(29).toString());
        assertThat(daily.get(29).path("day").asText()).isEqualTo(today.toString());

        assertSplit(daily.get(29).path("newListings"), 2, 0);
        assertSplit(daily.get(24).path("newListings"), 1, 0);
        assertSplit(daily.get(0).path("newListings"), 1, 0);
        assertSplit(daily.get(1).path("newListings"), 0, 0);
        assertSplit(daily.get(24).path("registrations"), 1, 0);
        // The seller and the administrator of this test's setup.
        assertSplit(daily.get(29).path("registrations"), 2, 0);
    }

    // ---- top makes -----------------------------------------------------------------------

    @Test
    @DisplayName("makes by published listings, most first, free-text makes included, at most ten")
    void topMakes() throws Exception {
        UUID popular = make("popular", "Populaire");
        UUID model = model(popular);
        listingOf(popular, model, "ACTIVE", ONLINE);
        listingOf(popular, model, "ACTIVE", ONLINE);
        listingOf(popular, model, "ACTIVE", ONLINE);
        // Sold: not published, so not counted.
        listingOf(popular, model, "SOLD", ONLINE);
        listing("ACTIVE", "APPROVED", ONLINE);

        UUID sold = make("gone", "Vendue");
        listingOf(sold, model(sold), "SOLD", ONLINE);

        JsonNode top = stats().path("topMakes");
        assertThat(top).hasSize(2);
        assertThat(top.get(0).path("make").asText()).isEqualTo("Populaire");
        assertSplit(top.get(0).path("published"), 3, 0);
        assertThat(top.get(1).path("make").asText()).isEqualTo("Lada");
        assertSplit(top.get(1).path("published"), 1, 0);

        for (int index = 0; index < 11; index++) {
            UUID other = make("make-" + index, "Marque " + index);
            listingOf(other, model(other), "ACTIVE", ONLINE);
        }
        assertThat(stats().path("topMakes")).hasSize(AdminStatsRepository.TOP_MAKES);
    }

    // ---- price verdicts ------------------------------------------------------------------

    @Test
    @DisplayName("published listings by verdict, by the same rule as the badge")
    void priceVerdicts() throws Exception {
        UUID make = make("verdict", "Verdict");
        UUID model = model(make);
        // Five at the market, one well under and one well over. The median of the others is
        // 15 000 € for each of the seven, and each has six comparables.
        for (int index = 0; index < 5; index++) {
            priced(make, model, 1_500_000);
        }
        priced(make, model, 1_000_000);
        priced(make, model, 2_000_000);
        // No model to compare on.
        listing("ACTIVE", "APPROVED", ONLINE);
        // Not published, so not in the distribution.
        listing("SOLD", "APPROVED", ONLINE);

        JsonNode verdicts = stats().path("priceVerdicts");

        assertSplit(verdicts.path("goodDeal"), 1, 0);
        assertSplit(verdicts.path("market"), 5, 0);
        assertSplit(verdicts.path("aboveMarket"), 1, 0);
        assertSplit(verdicts.path("noVerdict"), 1, 0);
    }

    // ---- real and demo -------------------------------------------------------------------

    @Test
    @DisplayName("what the ledger names is demo, everything else is real, in every figure")
    void demoAndRealApart() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE demo_seed_ledger (
                    entity_type VARCHAR(20) NOT NULL,
                    entity_id   UUID        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (entity_type, entity_id))""");

        UUID make = make("split", "Partagee");
        UUID model = model(make);
        UUID demoSeller = createUser(uniqueEmail("demo"), UserRole.PROFESSIONNEL, UserStatus.ACTIVE).getId();
        ledger("USER", demoSeller);
        // Two demo listings and one real, all of one make.
        UUID demoOne = listingOf(make, model, "ACTIVE", ONLINE);
        UUID demoTwo = listingOf(make, model, "ACTIVE", ONLINE);
        ledger("LISTING", demoOne);
        ledger("LISTING", demoTwo);
        UUID real = listingOf(make, model, "ACTIVE", ONLINE);
        // A sold demo listing.
        ledger("LISTING", listing("SOLD", "APPROVED", ONLINE));

        // One message from a demo account, two from real ones.
        UUID conversation = conversation(real, demoSeller);
        message(conversation, demoSeller, "now()");
        message(conversation, seller, "now()");
        message(conversation, seller, "now()");

        JsonNode stats = stats();

        assertThat(stats.path("demoDataPresent").asBoolean()).isTrue();
        assertSplit(stats.path("listings").path("published"), 1, 2);
        assertSplit(stats.path("listings").path("pendingModeration"), 1, 2);
        assertSplit(stats.path("listings").path("sold"), 0, 1);
        assertSplit(stats.path("last7Days").path("newListings"), 1, 3);
        // The seller and the administrator are real; the demo seller is not.
        assertSplit(stats.path("last7Days").path("registrations"), 2, 1);
        assertSplit(stats.path("last7Days").path("messages"), 2, 1);

        JsonNode today = stats.path("daily").get(29);
        assertSplit(today.path("newListings"), 1, 3);
        assertSplit(today.path("registrations"), 2, 1);

        assertSplit(stats.path("topMakes").get(0).path("published"), 1, 2);
        assertSplit(stats.path("priceVerdicts").path("noVerdict"), 1, 2);
    }

    @Test
    @DisplayName("an empty ledger means demo present and nothing demo")
    void emptyLedger() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE demo_seed_ledger (
                    entity_type VARCHAR(20) NOT NULL,
                    entity_id   UUID        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (entity_type, entity_id))""");
        listing("ACTIVE", "APPROVED", ONLINE);

        JsonNode stats = stats();

        assertThat(stats.path("demoDataPresent").asBoolean()).isTrue();
        assertSplit(stats.path("listings").path("published"), 1, 0);
    }

    // ---- natural-language search ---------------------------------------------------------

    @Test
    @DisplayName("search usage sums the last 7 and 30 Paris days, today included")
    void searchUsage() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        usage(today, 10, 1, 2);
        usage(today.minusDays(6), 5, 0, 1);
        // Just outside the seven.
        usage(today.minusDays(7), 100, 20, 30);
        usage(today.minusDays(29), 1000, 0, 0);
        // Just outside the thirty.
        usage(today.minusDays(30), 9999, 9, 9);

        JsonNode stats = stats();

        assertUsage(stats.path("searchLast7Days"), 15, 1, 3);
        assertUsage(stats.path("searchLast30Days"), 1115, 21, 33);
    }

    // ---- helpers -------------------------------------------------------------------------

    private JsonNode stats() throws Exception {
        return json(mockMvc.perform(get(STATS).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static void assertSplit(JsonNode split, long real, long demo) {
        assertThat(split.path("real").asLong()).as("real in " + split).isEqualTo(real);
        assertThat(split.path("demo").asLong()).as("demo in " + split).isEqualTo(demo);
    }

    private static void assertUsage(JsonNode usage, long requests, long failed, long withNotUnderstood) {
        assertThat(usage.path("requests").asLong()).isEqualTo(requests);
        assertThat(usage.path("failed").asLong()).isEqualTo(failed);
        assertThat(usage.path("withNotUnderstood").asLong()).isEqualTo(withNotUnderstood);
    }

    private JsonNode login(UserRole role) throws Exception {
        String email = uniqueEmail(role.name().toLowerCase());
        createUser(email, role, UserStatus.ACTIVE);
        return json(postJson("/api/v1/auth/login", Map.of("email", email, "password", "correct-horse-battery")));
    }

    private void ledger(String type, UUID id) {
        jdbcTemplate.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES (?, ?)", type, id);
    }

    private void usage(LocalDate day, int requests, int failed, int withNotUnderstood) {
        jdbcTemplate.update("""
                INSERT INTO search_interpret_usage (day, requests, failed, with_not_understood)
                VALUES (?, ?, ?, ?)""", day, requests, failed, withNotUnderstood);
    }

    private UUID make(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("DELETE FROM vehicle_model WHERE make_id IN (SELECT id FROM vehicle_make WHERE slug = ?)", slug);
        jdbcTemplate.update("DELETE FROM vehicle_make WHERE slug = ?", slug);
        jdbcTemplate.update("INSERT INTO vehicle_make (id, slug, name) VALUES (?, ?, ?)", id, slug, name);
        makes.add(id);
        return id;
    }

    private UUID model(UUID make) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vehicle_model (id, make_id, slug, name) VALUES (?, ?, 'modele', 'Modele')",
                id, make);
        return id;
    }

    /** A free-text Lada, so with no catalogue model and no price verdict. */
    private UUID listing(String status, String moderation, String expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code, postal_code, city,
                                     make_text, model_text,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Annonce', 1500000, 'EUR', 'FR', '75011', 'Paris', 'Lada', 'Niva',
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED', now(), %s, now(), now())
                """.formatted(expiresAt), id, seller, status, moderation);
        return id;
    }

    private UUID listingOf(UUID make, UUID model, String status, String expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code, postal_code, city,
                                     make_id, model_id,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                VALUES (?, ?, ?, 'NOT_REVIEWED', 'Annonce', 1500000, 'EUR', 'FR', '75011', 'Paris', ?, ?,
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED', now(), %s, now(), now())
                """.formatted(expiresAt), id, seller, status, make, model);
        return id;
    }

    /** Same car as its neighbours, down to the power, at this price: comparables of each other. */
    private void priced(UUID make, UUID model, int minorUnits) {
        UUID id = listingOf(make, model, "ACTIVE", ONLINE);
        jdbcTemplate.update("UPDATE listing SET price_amount = ?, power_hp = 100 WHERE id = ?", minorUnits, id);
    }

    private UUID listingCreated(String createdAt) {
        UUID id = listing("DRAFT", "NOT_REVIEWED", "NULL");
        jdbcTemplate.update("UPDATE listing SET created_at = %s WHERE id = ?".formatted(createdAt), id);
        return id;
    }

    private void userCreated(String createdAt) {
        UUID id = createUser(uniqueEmail("old"), UserRole.PARTICULIER, UserStatus.ACTIVE).getId();
        jdbcTemplate.update("UPDATE app_user SET created_at = %s WHERE id = ?".formatted(createdAt), id);
    }

    private UUID conversation(UUID listing, UUID buyer) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO conversation (id, listing_id, buyer_id, seller_id, created_at, last_message_at)
                VALUES (?, ?, ?, ?, now(), now())""", id, listing, buyer, seller);
        return id;
    }

    private void message(UUID conversation, UUID sender, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO message (id, conversation_id, sender_id, body, created_at)
                VALUES (?, ?, ?, 'Bonjour', %s)""".formatted(createdAt), UUID.randomUUID(), conversation, sender);
    }
}
