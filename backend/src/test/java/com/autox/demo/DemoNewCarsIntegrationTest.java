package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.autox.support.AbstractIntegrationTest;
import java.util.HashSet;
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

/** The demo's new cars, for the "Neuf" section: on a first seed, and added to a database already seeded. */
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = {
        "autox.demo.action=none",
        "autox.demo.listings=" + DemoNewCarsIntegrationTest.SEEDED,
        "autox.demo.damaged-listings=0",
        "autox.demo.leasing-listings=0",
        "autox.demo.new-listings=" + DemoNewCarsIntegrationTest.NEW})
class DemoNewCarsIntegrationTest extends AbstractIntegrationTest {

    static final int SEEDED = 2;
    static final int NEW = 3;

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

    /** The step's cars, by their titles: the first {@link #NEW} of the catalogue's. */
    private List<UUID> newIds() {
        List<String> titles = DemoCatalogue.NEW_CARS.subList(0, NEW).stream().map(car -> car.vehicle().title()).toList();
        return jdbcTemplate.queryForList("SELECT id FROM listing WHERE title = ANY(?) ORDER BY id", UUID.class,
                (Object) titles.toArray(String[]::new));
    }

    private void forgetNew() {
        for (UUID id : newIds()) {
            jdbcTemplate.update("DELETE FROM listing WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM demo_seed_ledger WHERE entity_id = ?", id);
        }
    }

    @Test
    @DisplayName("a first seed includes them: new, from dealers, online, recorded, and found by the 'Neuf' search")
    void firstSeed() throws Exception {
        assertThat(demoData.seed()).isEqualTo(SEEDED + NEW);

        assertThat(jdbcTemplate.queryForList("""
                SELECT l.status || ' ' || l.moderation_status || ' ' || l.vehicle_condition || ' ' || u.role
                  FROM listing l JOIN app_user u ON u.id = l.seller_id WHERE l.id = ANY(?)""", String.class,
                (Object) newIds().toArray(UUID[]::new)))
                .hasSize(NEW).containsOnly("ACTIVE APPROVED NEW PROFESSIONNEL");
        assertThat(ledger.ids(DemoLedger.LISTING)).containsAll(newIds());
        mockMvc.perform(get("/api/v1/listings/count").param("condition", "NEW"))
                .andExpect(jsonPath("$.count").value(NEW));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a start on a seeded database adds what is missing, touches nothing else, and again adds nothing")
    void addedToASeededDatabase(CapturedOutput output) {
        demoData.seed();
        forgetNew();
        List<Map<String, Object>> before = jdbcTemplate.queryForList("""
                SELECT id, status, moderation_status, title, price_amount, published_at, updated_at FROM listing ORDER BY id""");
        Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class);

        assertThat(demoData.seed()).as("no reseed").isZero();

        assertThat(newIds()).hasSize(NEW);
        assertThat(ledger.ids(DemoLedger.LISTING)).containsAll(newIds());
        assertThat(jdbcTemplate.queryForList("""
                SELECT id, status, moderation_status, title, price_amount, published_at, updated_at
                  FROM listing WHERE id <> ALL(?) ORDER BY id""", (Object) newIds().toArray(UUID[]::new)))
                .isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(users);
        assertThat(output).contains("Demo new cars: added " + NEW + " of " + NEW + "; other listings untouched.");

        List<UUID> added = newIds();
        demoData.seed();
        assertThat(newIds()).isEqualTo(added);
        assertThat(output).contains("Demo new cars: complete, nothing added (" + NEW + " cars).");
    }

    @Test
    @DisplayName("every one is new, from one of the four dealers, this year's, with a title and a description of its own")
    void theCatalogue() {
        List<DemoCatalogue.NewVehicle> cars = DemoCatalogue.NEW_CARS;
        assertThat(cars).hasSizeGreaterThanOrEqualTo(13);
        assertThat(cars).allSatisfy(car -> {
            assertThat(car.vehicle().condition()).isEqualTo(com.autox.listing.VehicleCondition.NEW);
            assertThat(car.vehicle().sellerKey())
                    .isIn("garage-bellevue", "auto-atlantique", "sud-automobiles", "nord-vo");
            assertThat(car.vehicle().year()).isEqualTo(2026);
            assertThat(car.vehicle().mileageKm()).isBetween(0, 50);
        });
        assertThat(new HashSet<>(cars.stream().map(car -> car.vehicle().title()).toList())).hasSameSizeAs(cars);
        assertThat(new HashSet<>(cars.stream().map(DemoDetails::newDescription).toList())).hasSameSizeAs(cars);
        // No title the other demo lists already use: titles are how a later start knows what it has.
        List<String> others = new java.util.ArrayList<>();
        DemoCatalogue.vehicles().forEach(vehicle -> others.add(vehicle.title()));
        DemoCatalogue.LEASED.forEach(leased -> others.add(leased.vehicle().title()));
        DemoCatalogue.DAMAGED.forEach(damaged -> others.add(damaged.vehicle().title()));
        assertThat(cars).noneMatch(car -> others.contains(car.vehicle().title()));
    }

    @Test
    @DisplayName("removal takes them with the rest")
    void removal() {
        demoData.seed();

        assertThat(demoData.remove()).isEqualTo(SEEDED + NEW);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isZero();
        assertThat(ledger.exists()).isFalse();
    }
}
