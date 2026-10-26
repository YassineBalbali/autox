package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.storage.ObjectStorage;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The demo seed, and above all its removal.
 *
 * <p>Seeding is the easy half. The half that matters is that removal takes away exactly
 * what the seed created — every row, every stored object — and nothing else, including
 * when a real make with the same slug was already in the catalogue.
 */
@ActiveProfiles({"test", "demo"})
// The runner would otherwise seed while the context starts, before any test has run.
// And six listings rather than sixty: each one is three real uploads with three resizes
// apiece, and nothing asserted here depends on how many there are.
// Caps of one listing per account, for both roles: lower than any deployment's, and lower
// than what a demo dealer owns, so every seed in this class runs into them.
@TestPropertySource(properties = {
        "autox.demo.action=none",
        "autox.demo.listings=" + DemoDataIntegrationTest.SEEDED,
        "autox.listing.particulier-active-limit=1",
        "autox.listing.professionnel-active-limit=1"})
class DemoDataIntegrationTest extends AbstractIntegrationTest {

    static final int SEEDED = 6;

    @Autowired DemoData demoData;
    @Autowired DemoLedger ledger;
    @Autowired VehicleMakeRepository makes;
    @Autowired ObjectStorage storage;
    @Autowired DemoAccounts demoAccounts;

    @AfterEach
    void leaveNothingBehind() {
        // The shared test database outlives this class, and the catalogue tables are not
        // among the ones the harness truncates between tests.
        demoData.remove();
        jdbcTemplate.update("DELETE FROM vehicle_model");
        jdbcTemplate.update("DELETE FROM vehicle_make");
    }

    @Test
    @DisplayName("seeding publishes every demo listing through the real pipeline")
    void seedProducesSearchableListings() throws Exception {
        int created = demoData.seed();

        assertThat(created).isEqualTo(SEEDED);
        mockMvc.perform(get("/api/v1/listings/count"))
                .andExpect(jsonPath("$.count").value(SEEDED));

        // Each one has a cover image with its variants, which is the evidence that the
        // upload went through the real confirm step rather than a row being inserted.
        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/v1/listings").param("size", "60"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page.get("items")).hasSize(SEEDED);
        page.get("items").forEach(item -> {
            assertThat(item.get("coverImage").has("CARD")).as(item.get("title").asText()).isTrue();
            assertThat(item.get("city").asText()).isNotBlank();
        });
    }

    @Test
    @DisplayName("the seed's own sellers are uncapped, so a deployment's listing caps cannot stop it halfway")
    void seedIgnoresDeploymentCaps() {
        // The public demo caps dealers at three, and a demo dealer owns more. The first seed
        // on a container with that cap failed partway through, leaving a ledger that made
        // every later start skip it.
        int created = demoData.seed();

        assertThat(created).isEqualTo(SEEDED);
        assertThat(jdbcTemplate.queryForList("SELECT active_listing_limit FROM app_user", Short.class))
                .isNotEmpty()
                .allSatisfy(limit -> assertThat(limit).isEqualTo((short) 0));
    }

    @Test
    @DisplayName("each listing has what its detail page shows: a gallery, equipment, a description")
    void seedFillsTheDetailPage() throws Exception {
        demoData.seed();

        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/v1/listings").param("size", "60"))
                .andReturn().getResponse().getContentAsString());
        String id = page.get("items").get(0).get("id").asText();

        JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/api/v1/listings/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Several photos, each with the variants the gallery uses.
        assertThat(detail.get("images")).hasSize(DemoData.PHOTOS_PER_LISTING);
        detail.get("images").forEach(image -> {
            assertThat(image.get("variants").has("FULL")).isTrue();
            assertThat(image.get("variants").has("THUMBNAIL")).isTrue();
        });
        assertThat(detail.get("featureCodes").size()).isGreaterThanOrEqualTo(8);
        assertThat(detail.get("description").asText()).contains("Annonce de démonstration");
        assertThat(detail.get("vehicle").get("doors").asInt()).isBetween(3, 5);
        assertThat(detail.get("vehicle").get("seats").asInt()).isBetween(4, 5);
        assertThat(detail.get("seller").get("memberSince").asText()).isNotBlank();
        // Nobody will answer a message to this seller, and the page says so.
        assertThat(detail.get("seller").get("demo").asBoolean()).isTrue();
        // Not there, by design of the public API — and the seed does not invent one.
        assertThat(detail.has("vin")).isFalse();
    }

    @Test
    @DisplayName("a demo seller is one the seed created, not any account in a seeded database")
    void demoSellersAreTheSeedsOwn() {
        demoData.seed();
        AppUser visitor = createUser(uniqueEmail("visitor"), UserRole.PARTICULIER, UserStatus.ACTIVE);

        assertThat(ledger.ids(DemoLedger.USER)).allSatisfy(id -> assertThat(demoAccounts.isDemoAccount(id)).isTrue());
        assertThat(demoAccounts.isDemoAccount(visitor.getId())).isFalse();

        // And once the demo is removed, its ledger with it, nobody is.
        demoData.remove();
        assertThat(demoAccounts.isDemoAccount(visitor.getId())).isFalse();
    }

    @Test
    @DisplayName("the seeded catalogue feeds the reference endpoints and the make filter")
    void seedMakesTheMakeFilterWork() throws Exception {
        demoData.seed();

        JsonNode makeList = objectMapper.readTree(mockMvc.perform(get("/api/v1/reference/makes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(makeList).hasSize(DemoCatalogue.MAKES.size());

        String peugeotId = null;
        for (JsonNode make : makeList) {
            if ("peugeot".equals(make.get("slug").asText())) {
                peugeotId = make.get("id").asText();
            }
        }
        assertThat(peugeotId).isNotNull();

        long expected = DemoCatalogue.vehicles().stream().limit(SEEDED)
                .filter(v -> v.makeSlug().equals("peugeot")).count();
        mockMvc.perform(get("/api/v1/listings/count").param("makeId", peugeotId))
                .andExpect(jsonPath("$.count").value((int) expected));
    }

    @Test
    @DisplayName("posting dates are spread out, so sorting by newest is not arbitrary")
    void seedSpreadsPublicationDates() throws Exception {
        demoData.seed();

        Integer distinctDays = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT published_at::date) FROM listing", Integer.class);
        assertThat(distinctDays).isGreaterThan(4);
    }

    @Test
    @DisplayName("seeding twice does not double the data")
    void seedIsIdempotent() throws Exception {
        demoData.seed();
        int second = demoData.seed();

        assertThat(second).isZero();
        mockMvc.perform(get("/api/v1/listings/count"))
                .andExpect(jsonPath("$.count").value(SEEDED));
    }

    @Test
    @DisplayName("removal deletes every row and every stored object the seed created")
    void removeLeavesNothing() throws Exception {
        demoData.seed();
        List<String> keys = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT storage_key FROM listing_image_variant", String.class));
        keys.addAll(jdbcTemplate.queryForList("SELECT storage_key FROM listing_image", String.class));
        assertThat(keys).isNotEmpty();
        assertThat(storage.stat(keys.getFirst())).isPresent();

        demoData.remove();

        for (String table : List.of("listing", "listing_image", "listing_image_variant",
                "listing_price_history", "vehicle_make", "vehicle_model", "pro_profile")) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE email LIKE ?", Integer.class,
                "%" + DemoCatalogue.EMAIL_DOMAIN)).isZero();
        // The objects too: rows without their files is half a clean-up, and the half left
        // behind is the half that costs money.
        assertThat(keys).allSatisfy(key -> assertThat(storage.stat(key)).as(key).isEmpty());
        assertThat(ledger.exists()).isFalse();
    }

    @Test
    @DisplayName("removal never deletes a make that was already in the catalogue")
    void removeSparesWhatItDidNotCreate() {
        // The case the ledger exists for. A real catalogue will contain "peugeot"; a
        // removal that worked by slug would delete it.
        VehicleMake real = makes.saveAndFlush(VehicleMake.of("peugeot", "Peugeot"));

        demoData.seed();
        demoData.remove();

        assertThat(makes.findById(real.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_make", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("removal keeps a demo make and model a visitor's listing uses, and finishes")
    void removeSparesWhatOthersUse() {
        // A visitor who sold a Peugeot 308 picked the demo's 308. Deleting it failed on the
        // foreign key halfway through a removal, after the demo listings had gone.
        demoData.seed();
        java.util.UUID[] demoModel = firstDemoModel();
        java.util.UUID makeId = demoModel[0];
        java.util.UUID modelId = demoModel[1];
        insertVisitorListing(makeId, modelId);

        assertThat(demoData.remove()).isEqualTo(SEEDED);

        assertThat(ledger.exists()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForList("SELECT id FROM vehicle_model", java.util.UUID.class))
                .containsExactly(modelId);
        assertThat(jdbcTemplate.queryForList("SELECT id FROM vehicle_make", java.util.UUID.class))
                .containsExactly(makeId);
        jdbcTemplate.update("DELETE FROM listing");
    }

    @Test
    @DisplayName("remove then seed works again and again, visitors' listings untouched, kept catalogue rows reused")
    void removeThenSeedRepeats() {
        // The production reset: remove, seed, and later the same again. A visitor's listing
        // on a demo model keeps that model through each removal; another on a make the demo
        // does not have is never touched. Neither may stop the next seed.
        assertThat(demoData.seed()).isEqualTo(SEEDED);
        java.util.UUID[] demoModel = firstDemoModel();
        java.util.UUID onDemoModel = insertVisitorListing(demoModel[0], demoModel[1]);
        java.util.UUID elsewhere = insertVisitorListing(null, null);

        for (int cycle = 1; cycle <= 2; cycle++) {
            assertThat(demoData.remove()).as("cycle %d: removed", cycle).isEqualTo(SEEDED);
            assertThat(ledger.exists()).as("cycle %d: ledger gone", cycle).isFalse();
            assertThat(listingIds()).as("cycle %d: after removal", cycle)
                    .containsExactlyInAnyOrder(onDemoModel, elsewhere);

            assertThat(demoData.seed()).as("cycle %d: seeded", cycle).isEqualTo(SEEDED);
            assertThat(ledger.ids(DemoLedger.LISTING)).as("cycle %d: recorded", cycle).hasSize(SEEDED);
            assertThat(listingIds()).as("cycle %d: after seeding", cycle)
                    .hasSize(SEEDED + 2).contains(onDemoModel, elsewhere);
            // The kept model is reused, not created twice, and not recorded as the seed's.
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vehicle_model WHERE id = ?",
                    Integer.class, demoModel[1])).isOne();
            assertThat(ledger.ids(DemoLedger.MODEL)).doesNotContain(demoModel[1]);
        }

        jdbcTemplate.update("DELETE FROM listing WHERE id IN (?, ?)", onDemoModel, elsewhere);
    }

    @Test
    @DisplayName("a seed stopped partway is not taken for a finished one, and remove then seed recovers")
    void interruptedSeedRecovers() throws Exception {
        // What a restart in the middle of a seed leaves: the accounts and catalogue, some
        // listings, the last of them a draft without its photos, and a ledger of all that.
        demoData.seed();
        java.util.UUID visitorListing = insertVisitorListing(null, null);
        List<java.util.UUID> recorded = ledger.ids(DemoLedger.LISTING);
        for (java.util.UUID id : recorded.subList(SEEDED - 2, SEEDED)) {
            storage.deleteAll(jdbcTemplate.queryForList("""
                    SELECT storage_key FROM listing_image WHERE listing_id = ?
                    UNION ALL
                    SELECT v.storage_key FROM listing_image_variant v
                      JOIN listing_image i ON i.id = v.image_id WHERE i.listing_id = ?""",
                    String.class, id, id));
            jdbcTemplate.update("DELETE FROM listing WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", id);
        }
        jdbcTemplate.update("UPDATE listing SET status = 'DRAFT', published_at = NULL WHERE id = ?",
                recorded.get(SEEDED - 3));

        // Started again, it does not pile a second seed on the first.
        assertThat(demoData.seed()).isZero();
        assertThat(listingIds()).hasSize(SEEDED - 2 + 1);

        // Remove takes the partial seed, draft included, and only that; then a full seed.
        assertThat(demoData.remove()).isEqualTo(SEEDED - 2);
        assertThat(listingIds()).containsExactly(visitorListing);
        assertThat(demoData.seed()).isEqualTo(SEEDED);
        assertThat(listingIds()).hasSize(SEEDED + 1).contains(visitorListing);

        jdbcTemplate.update("DELETE FROM listing WHERE id = ?", visitorListing);
    }

    /** The make and model ids of the first demo vehicle, as seeded. */
    private java.util.UUID[] firstDemoModel() {
        DemoCatalogue.Vehicle first = DemoCatalogue.vehicles().getFirst();
        java.util.UUID makeId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicle_make WHERE slug = ?", java.util.UUID.class, first.makeSlug());
        java.util.UUID modelId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicle_model WHERE make_id = ? AND slug = ?", java.util.UUID.class,
                makeId, first.modelSlug());
        return new java.util.UUID[] {makeId, modelId};
    }

    /**
     * A published listing by a visitor: on the given make and model, or, with nulls, on a
     * make typed in that the demo catalogue does not have.
     */
    private java.util.UUID insertVisitorListing(java.util.UUID makeId, java.util.UUID modelId) {
        AppUser visitor = createUser(uniqueEmail("visitor"), UserRole.PARTICULIER, UserStatus.ACTIVE);
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title,
                                     price_amount, price_currency_code, country_code,
                                     postal_code, city, make_id, model_id, make_text, model_text,
                                     first_registration_year, mileage_km, fuel_type, gearbox,
                                     body_type, vehicle_condition, published_at, expires_at,
                                     created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 'NOT_REVIEWED', 'Ma voiture',
                        1500000, 'EUR', 'FR', '75011', 'Paris', ?, ?, ?, ?,
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED',
                        now(), now() + interval '60 days', now(), now())
                """, id, visitor.getId(), makeId, modelId,
                makeId == null ? "Audi" : null, makeId == null ? "A3" : null);
        return id;
    }

    private List<java.util.UUID> listingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM listing", java.util.UUID.class);
    }

    @Test
    @DisplayName("removal with nothing seeded is a no-op")
    void removeWithoutSeed() {
        assertThat(demoData.remove()).isZero();
    }

    @Test
    @DisplayName("demo accounts cannot be signed in to with any known password")
    void demoAccountsHaveNoKnownPassword() {
        demoData.seed();

        // Nothing in the repository is a credential for these accounts. Each hash is of a
        // random value that was never written anywhere.
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT password_hash FROM app_user WHERE email LIKE ?", String.class,
                "%" + DemoCatalogue.EMAIL_DOMAIN);
        assertThat(hashes).hasSize(DemoCatalogue.SELLERS.size()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("without the demo profile, none of this exists")
    void demoBeansNeedTheProfile(@Autowired ApplicationContext context) {
        // Here the profile is on, so the bean is present — the assertion that matters is
        // the annotation, which is what keeps it out of every other context.
        assertThat(context.getBeansOfType(DemoRunner.class)).hasSize(1);
        for (Class<?> type : List.of(DemoRunner.class, DemoData.class, DemoLedger.class)) {
            org.springframework.context.annotation.Profile profile =
                    type.getAnnotation(org.springframework.context.annotation.Profile.class);
            assertThat(profile).as(type.getSimpleName() + " must be @Profile(\"demo\")").isNotNull();
            assertThat(profile.value()).containsExactly("demo");
        }
    }
}
