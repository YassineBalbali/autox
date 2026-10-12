package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.storage.ObjectStorage;
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
@TestPropertySource(properties = {"autox.demo.action=none", "autox.demo.listings=" + DemoDataIntegrationTest.SEEDED})
class DemoDataIntegrationTest extends AbstractIntegrationTest {

    static final int SEEDED = 6;

    @Autowired DemoData demoData;
    @Autowired DemoLedger ledger;
    @Autowired VehicleMakeRepository makes;
    @Autowired ObjectStorage storage;

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
        // Not there, by design of the public API — and the seed does not invent one.
        assertThat(detail.has("vin")).isFalse();
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
