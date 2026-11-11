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

/** The demo's leasing offers, on a first seed and added to a database already seeded. */
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = {
        "autox.demo.action=none",
        "autox.demo.new-listings=0",
        "autox.demo.listings=" + DemoLeasingOffersIntegrationTest.SEEDED,
        "autox.demo.damaged-listings=0",
        "autox.demo.leasing-listings=" + DemoLeasingOffersIntegrationTest.LEASED})
class DemoLeasingOffersIntegrationTest extends AbstractIntegrationTest {

    static final int SEEDED = 2;
    static final int LEASED = 3;

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

    private List<UUID> leasedIds() {
        return jdbcTemplate.queryForList("SELECT id FROM listing WHERE offer_type = 'LEASING' ORDER BY id", UUID.class);
    }

    private void forgetLeased() {
        for (UUID id : leasedIds()) {
            jdbcTemplate.update("DELETE FROM listing WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", id);
        }
    }

    @Test
    @DisplayName("a first seed includes them, approved, online, from dealers, and recorded")
    void firstSeed() throws Exception {
        assertThat(demoData.seed()).isEqualTo(SEEDED + LEASED);

        assertThat(jdbcTemplate.queryForList("""
                SELECT l.status || ' ' || l.moderation_status || ' ' || u.role
                  FROM listing l JOIN app_user u ON u.id = l.seller_id WHERE l.offer_type = 'LEASING'""", String.class))
                .hasSize(LEASED).containsOnly("ACTIVE APPROVED PROFESSIONNEL");
        assertThat(ledger.ids(DemoLedger.LISTING)).hasSize(SEEDED + LEASED).containsAll(leasedIds());
        // A search of their own, with their photos.
        mockMvc.perform(get("/api/v1/listings/count")).andExpect(jsonPath("$.count").value(SEEDED));
        mockMvc.perform(get("/api/v1/listings").param("offer", "LEASING"))
                .andExpect(jsonPath("$.items.length()").value(LEASED))
                .andExpect(jsonPath("$.items[0].leasing.totalCost").isNumber())
                .andExpect(jsonPath("$.items[0].coverImage.CARD").exists());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a start on a seeded database adds what is missing, touches nothing else, and again adds nothing")
    void addedToASeededDatabase(CapturedOutput output) {
        demoData.seed();
        forgetLeased();
        List<Map<String, Object>> before = jdbcTemplate.queryForList("""
                SELECT id, status, moderation_status, title, price_amount, published_at, updated_at FROM listing ORDER BY id""");
        Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class);

        assertThat(demoData.seed()).as("no reseed").isZero();

        assertThat(leasedIds()).hasSize(LEASED);
        assertThat(ledger.ids(DemoLedger.LISTING)).containsAll(leasedIds());
        assertThat(jdbcTemplate.queryForList("""
                SELECT id, status, moderation_status, title, price_amount, published_at, updated_at
                  FROM listing WHERE offer_type = 'SALE' ORDER BY id""")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(users);
        assertThat(output).contains("Demo leasing offers: added " + LEASED + " of " + LEASED + "; other listings untouched.");

        List<UUID> added = leasedIds();
        demoData.seed();
        assertThat(leasedIds()).isEqualTo(added);
        assertThat(output).contains("Demo leasing offers: complete, nothing added (" + LEASED + " offers).");
    }

    @Test
    @DisplayName("removal takes them with the rest")
    void removal() {
        demoData.seed();

        assertThat(demoData.remove()).isEqualTo(SEEDED + LEASED);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isZero();
        assertThat(ledger.exists()).isFalse();
    }
}
