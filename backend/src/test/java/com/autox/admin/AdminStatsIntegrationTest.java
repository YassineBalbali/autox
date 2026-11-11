package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * The admin dashboard's endpoint against a real database: who may read it, and every figure
 * against rows the test put there. Rows are inserted directly, because the point is the counting,
 * and because a date in the past cannot be made through the API.
 */
class AdminStatsIntegrationTest extends AbstractIntegrationTest {

    private static final String STATS = "/api/v1/admin/stats";
    private static final String ONLINE = "now() + interval '60 days'";

    @Autowired AdminStatsRepository repository;

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
                // Nothing may keep a copy, the browser included: it would outlive the caller's rights.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    // ---- empty --------------------------------------------------------------------------

    @Test
    @DisplayName("an empty site is zeros, an empty day for each of the week's days, nothing waiting, not an error")
    void empty() throws Exception {
        JsonNode stats = stats();

        assertThat(stats.path("demoDataPresent").asBoolean()).isFalse();
        for (String state : List.of("published", "pendingModeration", "rejected", "sold", "expired", "damaged", "sale", "leasing")) {
            assertSplit(stats.path("listings").path(state), 0, 0);
        }
        assertComparison(stats.at("/activity/newListings"), 0, 0, 0, 0);
        assertComparison(stats.at("/activity/messages"), 0, 0, 0, 0);
        assertThat(stats.path("series")).hasSize(7);
        assertThat(stats.path("topMakes")).isEmpty();
        assertSplit(stats.path("priceVerdicts").path("noVerdict"), 0, 0);
        assertThat(stats.at("/search/interpretRequests/current").asInt()).isZero();
        assertThat(stats.at("/search/topFilters")).isEmpty();
        assertThat(stats.at("/attention/openReports").asInt()).isZero();
        assertThat(stats.at("/attention/oldestPending")).isEmpty();
        // Absent, as an empty value is in every response.
        assertThat(stats.at("/attention/oldestPendingReal").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("the period is chosen, and an unknown one is refused")
    void periods() throws Exception {
        assertThat(stats().at("/period/key").asText()).isEqualTo("WEEK");
        for (String period : List.of("TODAY", "WEEK", "MONTH", "QUARTER")) {
            JsonNode stats = stats(period);
            assertThat(stats.at("/period/key").asText()).isEqualTo(period);
            assertThat(stats.at("/period/bucket").asText()).isEqualTo("TODAY".equals(period) ? "HOUR" : "DAY");
        }
        assertThat(stats("MONTH").path("series")).hasSize(30);
        assertThat(stats("QUARTER").path("series")).hasSize(90);
        mockMvc.perform(get(STATS).param("period", "YEAR").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());
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
    @DisplayName("each count falls in the period or in the one before it, and nowhere else")
    void periodAndPrevious() throws Exception {
        UUID recent = listingCreated("now() - interval '3 days'");
        listingCreated("now() - interval '8 days'");
        listingCreated("now() - interval '20 days'");
        listingCreated("now() - interval '40 days'");
        // Before both a week and the week before it.
        listingCreated("now() - interval '15 days'");

        userCreated("now() - interval '2 days'");
        userCreated("now() - interval '10 days'");
        userCreated("now() - interval '31 days'");

        UUID buyer = createUser(uniqueEmail("buyer"), UserRole.PARTICULIER, UserStatus.ACTIVE).getId();
        UUID conversation = conversation(recent, buyer);
        message(conversation, buyer, "now() - interval '1 day'");
        message(conversation, seller, "now() - interval '8 days'");
        message(conversation, buyer, "now() - interval '29 days'");
        message(conversation, seller, "now() - interval '35 days'");

        // Registered just now, in this test's setup: the seller, the administrator, the buyer.
        long justNow = 3;
        JsonNode week = stats("WEEK").path("activity");
        assertComparison(week.path("newListings"), 1, 0, 1, 0);
        assertComparison(week.path("registrations"), justNow + 1, 0, 1, 0);
        assertComparison(week.path("messages"), 1, 0, 1, 0);

        JsonNode month = stats("MONTH").path("activity");
        assertComparison(month.path("newListings"), 4, 0, 1, 0);
        assertComparison(month.path("registrations"), justNow + 2, 0, 1, 0);
        assertComparison(month.path("messages"), 3, 0, 1, 0);
    }

    @Test
    @DisplayName("today is compared with yesterday until the same time, not with all of yesterday")
    void todayAgainstYesterdaySoFar() throws Exception {
        listingCreated("now()");
        listingCreated("now() - interval '1 day' - interval '1 minute'");
        // Yesterday, but later in the day than now: not in the comparison.
        listingCreated("now() - interval '1 day' + interval '1 minute'");

        JsonNode stats = stats("TODAY");

        assertComparison(stats.at("/activity/newListings"), 1, 0, 1, 0);
        JsonNode series = stats.path("series");
        assertThat(series.get(0).path("start").asText()).endsWith("T00:00:00");
        LocalDateTime thisHour = LocalDateTime.now(ZoneId.of("Europe/Paris")).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        assertThat(series.get(series.size() - 1).path("start").asText()).isEqualTo(thisHour.toString().length() == 16
                ? thisHour + ":00" : thisHour.toString());
        assertSplit(series.get(series.size() - 1).path("newListings"), 1, 0);
    }

    // ---- whose clock ---------------------------------------------------------------------

    /**
     * Every row the dashboard counts is stamped by the database's clock ({@code now()}, a column
     * default or the outbox's {@code sent_at = now()}), so the period must end at the database's
     * now, not the application's. When the two drift apart (Docker's VM clock against Windows' in
     * a test run, Neon's against Render's in production) and the period ended at the application's
     * clock, a row written a moment before the request could fall after the end of the period and
     * not be counted: what SavedSearchIntegrationTest.dashboard once saw, "expected 2, was 1".
     */
    @ParameterizedTest(name = "application clock {0} s off the database's")
    @ValueSource(longs = {-5, 5, -3600, 3600})
    @DisplayName("the period ends at the database's now, whatever the application's clock says")
    void databaseClock(long skewSeconds) {
        Clock skewed = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(skewSeconds));
        AdminStatsService service = new AdminStatsService(repository, Duration.ZERO, skewed);
        outboxEmail("SENT", "now()");
        outboxEmail("SENT", "now() - interval '10 days'", "now() - interval '10 days'");
        // Just in the database's future: not sent yet as far as the period is concerned.
        outboxEmail("SENT", "now() + interval '2 seconds'", "now() + interval '2 seconds'");

        assertThat(service.stats(DashboardPeriod.TODAY).emails().sent().current()).isOne();
        assertThat(service.stats(DashboardPeriod.MONTH).emails().sent().current()).isEqualTo(2);
    }

    // ---- series --------------------------------------------------------------------------

    @Test
    @DisplayName("each day of the period, oldest first, ending today, each thing on its own day")
    void series() throws Exception {
        listingCreated("now()");
        listingCreated("now()");
        // At noon on a Paris day: a span of days counted back from now can cross a change of clocks.
        listingCreated(parisNoon(5));
        listingCreated(parisNoon(29));
        // Outside the thirty days.
        listingCreated(parisNoon(30));
        userCreated(parisNoon(5));
        jdbcTemplate.update("""
                INSERT INTO saved_search (id, user_id, name, criteria_version, criteria, link_params, last_digest_at, last_visited_at)
                VALUES (?, ?, 'Alerte', 1, '{}', '{}', now(), now())""", UUID.randomUUID(), seller);
        outboxEmail("SENT", "now()");

        JsonNode series = stats("MONTH").path("series");

        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        assertThat(series).hasSize(30);
        assertThat(series.get(0).path("start").asText()).isEqualTo(today.minusDays(29) + "T00:00:00");
        assertThat(series.get(29).path("start").asText()).isEqualTo(today + "T00:00:00");

        assertSplit(series.get(29).path("newListings"), 2, 0);
        assertSplit(series.get(24).path("newListings"), 1, 0);
        assertSplit(series.get(0).path("newListings"), 1, 0);
        assertSplit(series.get(1).path("newListings"), 0, 0);
        assertSplit(series.get(24).path("registrations"), 1, 0);
        // The seller and the administrator of this test's setup.
        assertSplit(series.get(29).path("registrations"), 2, 0);
        assertSplit(series.get(29).path("savedSearches"), 1, 0);
        assertThat(series.get(29).path("emailsSent").asLong()).isOne();
    }

    // ---- what is waiting -----------------------------------------------------------------

    @Test
    @DisplayName("the queue, its oldest listings in its own order, and open reports, whatever the period")
    void attention() throws Exception {
        UUID oldest = listing("ACTIVE", "NOT_REVIEWED", ONLINE);
        jdbcTemplate.update("UPDATE listing SET created_at = now() - interval '3 days' WHERE id = ?", oldest);
        UUID newer = listing("ACTIVE", "NOT_REVIEWED", ONLINE);
        // Reviewed already, and not online: neither is waiting.
        listing("ACTIVE", "APPROVED", ONLINE);
        listing("DRAFT", "NOT_REVIEWED", "NULL");
        UUID buyer = createUser(uniqueEmail("buyer"), UserRole.PARTICULIER, UserStatus.ACTIVE).getId();
        UUID conversation = conversation(newer, buyer);
        UUID reported = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO message (id, conversation_id, sender_id, body, created_at) VALUES (?, ?, ?, 'Arnaque', now())",
                reported, conversation, buyer);
        jdbcTemplate.update("""
                INSERT INTO message_report (id, message_id, reporter_id, reason, created_at)
                VALUES (?, ?, ?, 'SCAM', now() - interval '2 hours')""", UUID.randomUUID(), reported, seller);

        for (String period : List.of("TODAY", "QUARTER")) {
            JsonNode attention = stats(period).path("attention");

            assertSplit(attention.path("pendingModeration"), 2, 0);
            assertThat(attention.path("openReports").asLong()).isOne();
            assertThat(attention.path("oldestOpenReport").asText()).isNotEmpty();
            assertThat(attention.path("oldestPending")).hasSize(2);
            assertThat(attention.path("oldestPending").get(0).path("id").asText()).isEqualTo(oldest.toString());
            assertThat(attention.path("oldestPending").get(0).path("label").asText()).isEqualTo("Lada Niva");
            assertThat(attention.path("oldestPending").get(0).path("demo").asBoolean()).isFalse();
            assertThat(attention.path("oldestPendingReal").asText())
                    .startsWith(LocalDate.now(ZoneId.of("UTC")).minusDays(3).toString().substring(0, 7));
        }
    }

    @Test
    @DisplayName("the queue's five oldest, and the five oldest real ones, so the demo switch has both")
    void oldestPendingWithDemo() throws Exception {
        createLedger();
        List<UUID> demo = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = listing("ACTIVE", "NOT_REVIEWED", ONLINE);
            jdbcTemplate.update("UPDATE listing SET created_at = now() - make_interval(days => ?) WHERE id = ?", 20 - i, id);
            ledger("LISTING", id);
            demo.add(id);
        }
        UUID real = listing("ACTIVE", "NOT_REVIEWED", ONLINE);

        JsonNode oldest = stats().at("/attention/oldestPending");

        assertThat(oldest).hasSize(6);
        for (int i = 0; i < 5; i++) {
            assertThat(oldest.get(i).path("id").asText()).isEqualTo(demo.get(i).toString());
            assertThat(oldest.get(i).path("demo").asBoolean()).isTrue();
        }
        assertThat(oldest.get(5).path("id").asText()).isEqualTo(real.toString());
        assertThat(oldest.get(5).path("demo").asBoolean()).isFalse();
        assertSplit(stats().at("/attention/pendingModeration"), 1, 6);
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
        assertSplit(stats.at("/activity/newListings/current"), 1, 3);
        // The seller and the administrator are real; the demo seller is not.
        assertSplit(stats.at("/activity/registrations/current"), 2, 1);
        assertSplit(stats.at("/activity/messages/current"), 2, 1);

        JsonNode today = stats.path("series").get(6);
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
    @DisplayName("natural-language search sums the period's Paris days, today included, and the days before")
    void interpretUsage() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        usage(today, 10, 1, 2);
        usage(today.minusDays(6), 5, 0, 1);
        // The week before.
        usage(today.minusDays(7), 100, 20, 30);
        usage(today.minusDays(13), 1, 1, 0);
        // Before that.
        usage(today.minusDays(14), 9999, 9, 9);

        JsonNode search = stats("WEEK").path("search");

        assertCount(search.path("interpretRequests"), 15, 101);
        assertCount(search.path("interpretFailed"), 1, 21);
        assertCount(search.path("interpretNotUnderstood"), 3, 30);
        // Stored by day: today is set against the whole of yesterday.
        assertCount(stats("TODAY").at("/search/interpretRequests"), 10, 0);
    }

    @Test
    @DisplayName("the filters searches use, by name, most used first, with how many searches there were")
    void filters() throws Exception {
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "DIESEL").param("priceMax", "1500000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/listings").param("fuelType", "PETROL")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/listings").param("q", "golf gti")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        // The same search read further is not another search.
        listing("ACTIVE", "APPROVED", ONLINE);
        listing("ACTIVE", "APPROVED", ONLINE);
        String next = json(mockMvc.perform(get("/api/v1/listings").param("size", "1").param("fuelType", "PETROL"))
                .andReturn()).path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/listings").param("size", "1").param("fuelType", "PETROL").param("cursor", next))
                .andExpect(status().isOk());
        jdbcTemplate.update("""
                INSERT INTO search_filter_usage (hour, filter, searches)
                VALUES (date_trunc('hour', now() - interval '8 days'), 'make', 4),
                       (date_trunc('hour', now() - interval '8 days'), '_all', 6)""");

        JsonNode search = stats("WEEK").path("search");

        assertCount(search.path("searches"), 5, 6);
        JsonNode top = search.path("topFilters");
        assertThat(top.get(0).path("filter").asText()).isEqualTo("fuelType");
        assertThat(top.get(0).path("current").asLong()).isEqualTo(3);
        assertThat(top).extracting(node -> node.path("filter").asText()).containsExactlyInAnyOrder("fuelType", "price", "text");
        // Names only: nothing anyone searched for is stored.
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT filter FROM search_filter_usage", String.class))
                .containsExactlyInAnyOrder("_all", "fuelType", "price", "text", "make");
    }

    @Test
    @DisplayName("active alerts are counted now, whatever the period")
    void alerts() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO saved_search (id, user_id, name, criteria_version, criteria, link_params, last_digest_at, last_visited_at, paused_at)
                VALUES (?, ?, 'Active', 1, '{}', '{}', now(), now(), NULL), (?, ?, 'En pause', 1, '{}', '{}', now(), now(), now())""",
                UUID.randomUUID(), seller, UUID.randomUUID(), seller);

        assertThat(stats("TODAY").at("/search/activeAlerts").asLong()).isOne();
    }

    // ---- transactional email ---------------------------------------------------------------

    @Test
    @DisplayName("emails sent and failed in the period and the one before; one still being retried is neither")
    void emails() throws Exception {
        outboxEmail("SENT", "now()");
        outboxEmail("SENT", "now() - interval '8 days'", "now() - interval '8 days'");
        outboxEmail("SENT", "now() - interval '31 days'", "now() - interval '31 days'");
        outboxEmail("FAILED", "now() - interval '1 day'");
        outboxEmail("FAILED", "now() - interval '9 days'");
        outboxEmail("FAILED", "now() - interval '40 days'");
        jdbcTemplate.update("""
                INSERT INTO outbox_email (id, recipient, subject, body_text, body_html, email_type)
                VALUES (?, 'r@autox.test', 'Objet', 'T', 'H', 'NEW_MESSAGE')
                """, UUID.randomUUID());

        JsonNode emails = stats("WEEK").path("emails");

        assertCount(emails.path("sent"), 1, 1);
        assertCount(emails.path("failed"), 1, 1);
        assertCount(emails.path("alertsSent"), 0, 0);
    }

    // ---- helpers -------------------------------------------------------------------------

    private void outboxEmail(String status, String createdAt) {
        outboxEmail(status, createdAt, "now()");
    }

    private void outboxEmail(String status, String createdAt, String sentAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox_email (id, recipient, subject, email_type, status, sent_at, created_at)
                VALUES (?, 'r@autox.test', 'Objet', 'NEW_MESSAGE', ?, %s, %s)
                """.formatted("SENT".equals(status) ? sentAt : "NULL", createdAt), UUID.randomUUID(), status);
    }

    private JsonNode stats() throws Exception {
        return json(mockMvc.perform(get(STATS).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode stats(String period) throws Exception {
        return json(mockMvc.perform(get(STATS).param("period", period).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** Noon, Paris time, so many Paris days ago. */
    private static String parisNoon(int daysAgo) {
        return "(((now() AT TIME ZONE 'Europe/Paris')::date - %d) + time '12:00') AT TIME ZONE 'Europe/Paris'".formatted(daysAgo);
    }

    private void createLedger() {
        jdbcTemplate.execute("""
                CREATE TABLE demo_seed_ledger (
                    entity_type VARCHAR(20) NOT NULL,
                    entity_id   UUID        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (entity_type, entity_id))""");
    }

    private static void assertComparison(JsonNode comparison, long real, long demo, long previousReal, long previousDemo) {
        assertSplit(comparison.path("current"), real, demo);
        assertSplit(comparison.path("previous"), previousReal, previousDemo);
    }

    private static void assertCount(JsonNode count, long current, long previous) {
        assertThat(count.path("current").asLong()).as("current in " + count).isEqualTo(current);
        assertThat(count.path("previous").asLong()).as("previous in " + count).isEqualTo(previous);
    }

    private static void assertSplit(JsonNode split, long real, long demo) {
        assertThat(split.path("real").asLong()).as("real in " + split).isEqualTo(real);
        assertThat(split.path("demo").asLong()).as("demo in " + split).isEqualTo(demo);
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
