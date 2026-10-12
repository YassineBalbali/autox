package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.user.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The demo data set itself — no database, no Spring.
 *
 * <p>The seed is partly generated, and generated data is only useful if it is the same
 * every time and valid every time. These are the properties the seeder relies on without
 * checking: a listing whose model is not in the demo catalogue, or a private seller given
 * more listings than the API lets them publish, would fail halfway through a seed and
 * leave a developer with thirty-one cars and a stack trace.
 */
class DemoCatalogueTest {

    private final List<DemoCatalogue.Vehicle> vehicles = DemoCatalogue.vehicles();

    @Test
    @DisplayName("there are enough listings for more than two pages of results")
    void enoughForPagination() {
        // A page is 24. Fewer than 49 and "voir plus" can be pressed at most once, which
        // does not exercise a cursor issued by a page that was itself fetched by cursor.
        assertThat(vehicles).hasSize(DemoCatalogue.SIZE);
        assertThat(DemoCatalogue.SIZE).isGreaterThanOrEqualTo(49);
    }

    @Test
    @DisplayName("the set is identical on every call")
    void deterministic() {
        // Same cars on every machine: a screenshot in a bug report can be reproduced.
        assertThat(DemoCatalogue.vehicles()).isEqualTo(vehicles);
    }

    @Test
    @DisplayName("every listing refers to a make and model the seed creates")
    void catalogueReferencesResolve() {
        Set<String> known = DemoCatalogue.MAKES.stream()
                .flatMap(make -> make.models().stream().map(model -> make.slug() + "/" + model.slug()))
                .collect(Collectors.toSet());

        assertThat(vehicles).allSatisfy(vehicle ->
                assertThat(known).as(vehicle.title()).contains(vehicle.makeSlug() + "/" + vehicle.modelSlug()));
    }

    @Test
    @DisplayName("every listing belongs to a seller the seed creates")
    void sellersResolve() {
        Set<String> sellers = DemoCatalogue.SELLERS.stream()
                .map(DemoCatalogue.Seller::key).collect(Collectors.toSet());

        assertThat(vehicles).allSatisfy(vehicle -> assertThat(sellers).contains(vehicle.sellerKey()));
    }

    @Test
    @DisplayName("no private seller is given more listings than the API lets them publish")
    void privateSellersStayWithinTheirLimit() {
        // application.yml: particulier-active-limit is 5. One over and the publish step
        // answers ACTIVE_LISTING_LIMIT_REACHED in the middle of the seed.
        Map<String, UserRole> roles = DemoCatalogue.SELLERS.stream()
                .collect(Collectors.toMap(DemoCatalogue.Seller::key, DemoCatalogue.Seller::role));
        Map<String, Long> perSeller = vehicles.stream()
                .collect(Collectors.groupingBy(DemoCatalogue.Vehicle::sellerKey, Collectors.counting()));

        perSeller.forEach((seller, count) -> {
            if (roles.get(seller) == UserRole.PARTICULIER) {
                assertThat(count).as(seller).isLessThanOrEqualTo(5);
            }
        });
    }

    @Test
    @DisplayName("the values are ones the listing API accepts")
    void valuesAreValid() {
        assertThat(vehicles).allSatisfy(vehicle -> {
            assertThat(vehicle.title()).as("title").isNotBlank().hasSizeLessThanOrEqualTo(140);
            assertThat(vehicle.priceEuros()).as(vehicle.title()).isBetween(1_000L, 200_000L);
            assertThat(vehicle.year()).isBetween(2000, 2026);
            assertThat(vehicle.month()).isBetween(1, 12);
            assertThat(vehicle.mileageKm()).isBetween(0, 400_000);
            assertThat(vehicle.powerHp()).isBetween(40, 700);
            assertThat(vehicle.postalCode()).matches("\\d{5}");
            assertThat(vehicle.publishedDaysAgo()).isBetween(0, 120);
        });
    }

    @Test
    @DisplayName("the set gives every filter something to find")
    void coversTheFilters() {
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::fuel).distinct())
                .contains(FuelType.PETROL, FuelType.DIESEL, FuelType.ELECTRIC,
                        FuelType.HYBRID_PETROL, FuelType.PLUGIN_HYBRID, FuelType.LPG);
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::gearbox).distinct())
                .contains(Gearbox.MANUAL, Gearbox.AUTOMATIC);
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::body).distinct()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::city).distinct()).hasSizeGreaterThanOrEqualTo(8);
        // Every make in the demo catalogue has at least one car, so no make filter is empty.
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::makeSlug).distinct())
                .hasSize(DemoCatalogue.MAKES.size());
    }

    @Test
    @DisplayName("every equipment code the seed uses is one the catalogue of features defines")
    void featureCodesAreKnown() {
        // The codes seeded by V5. A code that is not among them is refused by the listing
        // API, halfway through a seed.
        Set<String> defined = Set.of(
                "CRUISE_CONTROL", "ADAPTIVE_CRUISE", "PARKING_SENSORS_REAR", "PARKING_SENSORS_FRONT",
                "REVERSING_CAMERA", "LANE_ASSIST", "BLIND_SPOT_MONITOR", "AIR_CONDITIONING",
                "CLIMATE_CONTROL_AUTO", "HEATED_SEATS", "ELECTRIC_SEATS", "KEYLESS_ENTRY",
                "ELECTRIC_WINDOWS", "ALLOY_WHEELS", "LED_HEADLIGHTS", "XENON_HEADLIGHTS",
                "PANORAMIC_ROOF", "SUNROOF", "TOW_BAR", "ROOF_RAILS", "LEATHER_STEERING",
                "AMBIENT_LIGHTING", "BLUETOOTH", "APPLE_CARPLAY", "ANDROID_AUTO", "NAVIGATION",
                "DAB_RADIO", "START_STOP", "PARTICLE_FILTER", "ABS", "ESP", "EMERGENCY_BRAKING",
                "ISOFIX", "TYRE_PRESSURE_MONITOR");

        assertThat(vehicles).allSatisfy(vehicle -> {
            assertThat(defined).as(vehicle.title()).containsAll(DemoDetails.features(vehicle));
            assertThat(DemoDetails.features(vehicle)).hasSizeGreaterThanOrEqualTo(8);
        });
    }

    @Test
    @DisplayName("the derived details are plausible, and the same on every call")
    void derivedDetailsAreSane() {
        assertThat(vehicles).allSatisfy(vehicle -> {
            assertThat(DemoDetails.features(vehicle)).isEqualTo(DemoDetails.features(vehicle));
            assertThat(DemoDetails.doors(vehicle)).isBetween(3, 5);
            assertThat(DemoDetails.co2(vehicle)).isBetween(0, 300);
            assertThat(DemoDetails.description(vehicle)).contains("Annonce de démonstration");
            if (vehicle.fuel() == FuelType.ELECTRIC) {
                // A battery car that emitted CO₂, or carried a particle filter, would be
                // the kind of nonsense that makes demo data distracting.
                assertThat(DemoDetails.co2(vehicle)).isZero();
                assertThat(DemoDetails.features(vehicle)).doesNotContain("PARTICLE_FILTER", "START_STOP");
            }
        });
    }

    @Test
    @DisplayName("posting dates and prices are spread, so each sort order gives a different list")
    void sortOrdersDiffer() {
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::publishedDaysAgo).distinct())
                .hasSizeGreaterThan(25);
        assertThat(vehicles.stream().map(DemoCatalogue.Vehicle::priceEuros).distinct())
                .hasSizeGreaterThan(30);
    }
}
