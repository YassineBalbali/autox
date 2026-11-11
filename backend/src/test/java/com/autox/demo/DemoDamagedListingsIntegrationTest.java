package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.autox.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** The demo's damaged vehicles, on a first seed and added to a database already seeded. */
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = {
        "autox.demo.action=none",
        "autox.demo.new-listings=0",
        "autox.demo.listings=" + DemoDamagedListingsIntegrationTest.SEEDED,
        "autox.demo.damaged-listings=" + DemoDamagedListingsIntegrationTest.DAMAGED,
        "autox.demo.leasing-listings=0"})
class DemoDamagedListingsIntegrationTest extends AbstractIntegrationTest {

    static final int SEEDED = 2;
    static final int DAMAGED = 3;

    @Autowired DemoData demoData;
    @Autowired DemoLedger ledger;

    @BeforeEach
    void startFromAnEmptyCatalogue() {
        clearCatalogue();
    }

    @AfterEach
    void leaveNothingBehind() {
        demoData.remove();
        clearCatalogue();
    }

    private void clearCatalogue() {
        jdbcTemplate.update("DELETE FROM vehicle_trim");
        jdbcTemplate.update("DELETE FROM vehicle_model");
        jdbcTemplate.update("DELETE FROM vehicle_make");
    }

    private List<UUID> damagedIds() {
        return jdbcTemplate.queryForList("SELECT id FROM listing WHERE damaged ORDER BY id", UUID.class);
    }

    /** What an older seed left: everything but the damaged vehicles. */
    private void forgetDamaged() {
        for (UUID id : damagedIds()) {
            jdbcTemplate.update("DELETE FROM listing WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", id);
        }
    }

    private List<Map<String, Object>> everything() {
        return jdbcTemplate.queryForList("""
                SELECT id, status, moderation_status, title, price_amount, published_at, updated_at
                  FROM listing WHERE NOT damaged ORDER BY id""");
    }

    @Test
    @DisplayName("a first seed includes them, approved, online, declared and recorded")
    void firstSeed() throws Exception {
        assertThat(demoData.seed()).isEqualTo(SEEDED + DAMAGED);

        assertThat(jdbcTemplate.queryForList("""
                SELECT status || ' ' || moderation_status FROM listing WHERE damaged""", String.class))
                .hasSize(DAMAGED).containsOnly("ACTIVE APPROVED");
        assertThat(ledger.ids(DemoLedger.LISTING)).hasSize(SEEDED + DAMAGED).containsAll(damagedIds());
        // Out of a default search like any damaged vehicle, and there on request, with their photos.
        mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(SEEDED));
        mockMvc.perform(get("/api/v1/listings").param("damage", "DAMAGED_ONLY"))
                .andExpect(jsonPath("$.items.length()").value(DAMAGED))
                .andExpect(jsonPath("$.items[0].damage.description").isNotEmpty())
                .andExpect(jsonPath("$.items[0].coverImage.CARD").exists());
        // Nothing in the listing contradicts what the seller declared.
        assertThat(jdbcTemplate.queryForList("SELECT description FROM listing WHERE damaged", String.class))
                .allSatisfy(text -> assertThat(text).doesNotContain("très bon état").contains("démonstration"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a start on a seeded database adds what is missing, and touches nothing else")
    void addedToASeededDatabase(CapturedOutput output) {
        demoData.seed();
        forgetDamaged();
        List<Map<String, Object>> before = everything();
        Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class);

        assertThat(demoData.seed()).as("no reseed").isZero();

        assertThat(damagedIds()).hasSize(DAMAGED);
        assertThat(ledger.ids(DemoLedger.LISTING)).containsAll(damagedIds());
        assertThat(everything()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(users);
        assertThat(output).contains("Demo damaged listings: added " + DAMAGED + " of " + DAMAGED + "; other listings untouched.");
        assertThat(output).contains("Demo data is already present (" + (SEEDED + DAMAGED) + " listings); nothing seeded.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("starting again adds nothing, and only the ones missing are added")
    void idempotent(CapturedOutput output) {
        demoData.seed();
        List<UUID> first = damagedIds();

        demoData.seed();
        assertThat(damagedIds()).isEqualTo(first);
        assertThat(output).contains("Demo damaged listings: complete, nothing added (" + DAMAGED + " listings).");

        UUID lost = first.getFirst();
        jdbcTemplate.update("DELETE FROM listing WHERE id = ?", lost);
        jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", lost);
        demoData.seed();

        assertThat(damagedIds()).hasSize(DAMAGED).containsAll(first.subList(1, DAMAGED)).doesNotContain(lost);
        assertThat(output).contains("Demo damaged listings: added 1 of " + DAMAGED);
    }

    @Test
    @DisplayName("a visitor's listing with the same title is not taken for one of them")
    void visitorsTitle() {
        demoData.seed();
        String title = DemoCatalogue.DAMAGED.getFirst().vehicle().title();
        UUID demo = jdbcTemplate.queryForObject("SELECT id FROM listing WHERE title = ?", UUID.class, title);
        jdbcTemplate.update("DELETE FROM listing WHERE id = ?", demo);
        jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", demo);
        UUID seller = ledger.ids(DemoLedger.USER).getFirst();
        UUID visitor = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO listing (id, seller_id, status, moderation_status, title, price_amount, price_currency_code,
                                     country_code, postal_code, city, make_text, model_text, first_registration_year,
                                     mileage_km, fuel_type, gearbox, body_type, vehicle_condition, created_at, updated_at)
                VALUES (?, ?, 'DRAFT', 'NOT_REVIEWED', ?, 1500000, 'EUR', 'FR', '75011', 'Paris', 'Audi', 'A3',
                        2021, 40000, 'PETROL', 'MANUAL', 'HATCHBACK', 'USED', now(), now())""", visitor, seller, title);

        assertThat(demoData.addMissingDamaged()).isOne();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing WHERE title = ?", Integer.class, title)).isEqualTo(2);
        assertThat(ledger.ids(DemoLedger.LISTING)).doesNotContain(visitor);
        jdbcTemplate.update("DELETE FROM listing WHERE id = ?", visitor);
    }

    @Test
    @DisplayName("removal takes them with the rest")
    void removal() {
        demoData.seed();
        forgetDamaged();
        demoData.seed();

        assertThat(demoData.remove()).isEqualTo(SEEDED + DAMAGED);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isZero();
        assertThat(ledger.exists()).isFalse();
    }
}
