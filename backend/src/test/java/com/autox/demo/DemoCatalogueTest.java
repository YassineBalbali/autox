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
        // The six the home page offers a tile for, each with a few listings behind it.
        Map<com.autox.catalog.BodyType, Long> perBody = vehicles.stream()
                .collect(Collectors.groupingBy(DemoCatalogue.Vehicle::body, Collectors.counting()));
        assertThat(perBody).containsKeys(
                com.autox.catalog.BodyType.HATCHBACK, com.autox.catalog.BodyType.SEDAN,
                com.autox.catalog.BodyType.ESTATE, com.autox.catalog.BodyType.SUV,
                com.autox.catalog.BodyType.MPV, com.autox.catalog.BodyType.VAN);
        assertThat(perBody.values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(3));
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

    // ---- plausible to someone who knows cars -------------------------------------------

    @Test
    @DisplayName("no car is registered before its model went on sale in France")
    void noCarPredatesItsModel() {
        // The bug that prompted these checks: a 2017 Tesla Model Y, three years before there
        // was one.
        assertThat(vehicles).allSatisfy(vehicle -> {
            String model = vehicle.makeSlug() + "/" + vehicle.modelSlug();
            assertThat(DemoCatalogue.LAUNCH_YEARS).as("launch year known for " + model).containsKey(model);
            assertThat(vehicle.year()).as(vehicle.title()).isGreaterThanOrEqualTo(DemoCatalogue.LAUNCH_YEARS.get(model));
        });
    }

    @Test
    @DisplayName("a generated car is registered in a year its version was sold, and not in the future")
    void generatedYearsAreThoseTheVersionWasSold() {
        assertThat(generated()).allSatisfy(vehicle -> {
            DemoCatalogue.Variant variant = variantOf(vehicle);
            assertThat(vehicle.year()).as(vehicle.title())
                    .isBetween(variant.firstYear(), Math.min(variant.lastYear(), DemoCatalogue.REFERENCE_YEAR - 1));
        });
    }

    @Test
    @DisplayName("the power is the one the version's name states")
    void powerMatchesTheVersion() {
        // "TCe 90" is 90 ch, "330e 292" is 292. A number glued to letters ("sDrive18i",
        // "318d") or with a decimal point ("1.5") is a model or an engine size, not a power.
        java.util.regex.Pattern figure = java.util.regex.Pattern.compile("(?<![\\w.])(\\d{2,3})h?(?![\\w.])");
        assertThat(vehicles).allSatisfy(vehicle -> {
            List<Integer> stated = figure.matcher(vehicle.version()).results()
                    .map(match -> Integer.parseInt(match.group(1))).toList();
            if (!stated.isEmpty()) {
                assertThat(stated).as(vehicle.version()).contains(vehicle.powerHp());
            }
        });
    }

    @Test
    @DisplayName("a used car is priced below its version new, and above a scrap price")
    void pricesAreBelievable() {
        assertThat(generated()).allSatisfy(vehicle -> {
            double share = vehicle.priceEuros() / (double) variantOf(vehicle).newPriceEuros();
            assertThat(share).as(vehicle.title() + " " + vehicle.year() + ", " + vehicle.mileageKm() + " km")
                    .isBetween(0.20, 0.95);
        });
    }

    @Test
    @DisplayName("a title that claims a low mileage has one")
    void lowMileageClaimsAreTrue() {
        // Found on a 2020 3008 with 88 700 km, an average mileage, sold as "faible kilométrage".
        assertThat(vehicles.stream().filter(vehicle -> vehicle.title().contains("faible kilométrage")))
                .isNotEmpty()
                .allSatisfy(vehicle -> assertThat(vehicle.mileageKm()).as(vehicle.title())
                        .isLessThanOrEqualTo((int) (0.8 * 13_000
                                * DemoCatalogue.ageInYears(vehicle.year(), vehicle.month()))));
    }

    @Test
    @DisplayName("an older or more driven car of the same version is asked less for")
    void depreciationRunsTheRightWay() {
        double newer = DemoCatalogue.askingPrice(30_000, FuelType.PETROL, 2, 26_000, 1.0);
        double older = DemoCatalogue.askingPrice(30_000, FuelType.PETROL, 5, 65_000, 1.0);
        double driven = DemoCatalogue.askingPrice(30_000, FuelType.PETROL, 2, 80_000, 1.0);

        assertThat(older).isLessThan(newer);
        assertThat(driven).isLessThan(newer);
        // An electric car loses value faster than the same price of petrol car.
        assertThat(DemoCatalogue.askingPrice(30_000, FuelType.ELECTRIC, 5, 65_000, 1.0)).isLessThan((long) older);
    }

    @Test
    @DisplayName("every volume model gives the price indicator enough to work with, at all three levels")
    void priceIndicatorHasSomethingToShow() {
        // The rule of com.autox.search.MarketPrice, applied to the seed in memory: same
        // model, fuel and power within 15 %, year within two, same mileage band, not new,
        // at least five others, their prices brought to the listing's age and mileage.
        Map<String, Long> levels = new java.util.HashMap<>();
        Set<String> judgedModels = new java.util.HashSet<>();
        Map<com.autox.catalog.BodyType, Long> judgedBodies = new java.util.EnumMap<>(com.autox.catalog.BodyType.class);
        for (DemoCatalogue.Vehicle vehicle : vehicles) {
            if (vehicle.condition() == com.autox.listing.VehicleCondition.NEW) {
                continue;
            }
            List<Double> comparables = vehicles.stream()
                    .filter(other -> other != vehicle)
                    .filter(other -> other.condition() != com.autox.listing.VehicleCondition.NEW)
                    .filter(other -> other.makeSlug().equals(vehicle.makeSlug())
                            && other.modelSlug().equals(vehicle.modelSlug()))
                    .filter(other -> other.fuel() == vehicle.fuel()
                            && Math.abs(other.powerHp() - vehicle.powerHp()) <= 0.15 * vehicle.powerHp())
                    .filter(other -> Math.abs(other.year() - vehicle.year()) <= 2)
                    .filter(other -> band(other.mileageKm()) == band(vehicle.mileageKm()))
                    .map(other -> adjusted(other, vehicle)).sorted().toList();
            if (comparables.size() < 5) {
                continue;
            }
            int n = comparables.size();
            double median = n % 2 == 1 ? comparables.get(n / 2)
                    : (comparables.get(n / 2 - 1) + comparables.get(n / 2)) / 2.0;
            String level = vehicle.priceEuros() <= 0.9 * median ? "GOOD_DEAL"
                    : vehicle.priceEuros() >= 1.1 * median ? "ABOVE_MARKET" : "MARKET";
            levels.merge(level, 1L, Long::sum);
            judgedModels.add(vehicle.makeSlug() + "/" + vehicle.modelSlug());
            judgedBodies.merge(vehicle.body(), 1L, Long::sum);
        }

        // The volume models exist for this: if one of them has no verdict at all, its
        // listings are spread over too many versions, years or mileage bands.
        Set<String> volumeModels = DemoCatalogue.TEMPLATES.stream()
                .filter(template -> template.weight() >= 9)
                .map(template -> template.makeSlug() + "/" + template.modelSlug())
                .collect(Collectors.toSet());
        assertThat(volumeModels).hasSize(10);
        assertThat(judgedModels).containsAll(volumeModels);
        // And every body type on the home page has listings that show a verdict, not
        // only the cars and SUVs.
        assertThat(judgedBodies).as("listings with an indicator, by body").containsKeys(
                com.autox.catalog.BodyType.HATCHBACK, com.autox.catalog.BodyType.SEDAN,
                com.autox.catalog.BodyType.SUV, com.autox.catalog.BodyType.MPV, com.autox.catalog.BodyType.VAN);

        assertThat(levels.values().stream().mapToLong(Long::longValue).sum())
                .as("listings with an indicator").isGreaterThanOrEqualTo(30);
        assertThat(levels).as("each level appears").containsKeys("GOOD_DEAL", "MARKET", "ABOVE_MARKET");
        assertThat(levels.get("GOOD_DEAL")).isGreaterThanOrEqualTo(4);
        assertThat(levels.get("ABOVE_MARKET")).isGreaterThanOrEqualTo(4);
    }

    /** {@code MarketPrice}'s adjustment: the comparable's price at the listing's age and mileage. */
    private static double adjusted(DemoCatalogue.Vehicle comparable, DemoCatalogue.Vehicle listing) {
        double years = (listing.year() + (listing.month() - 1) / 12.0)
                - (comparable.year() + (comparable.month() - 1) / 12.0);
        return comparable.priceEuros()
                * Math.pow(1 / (1 - com.autox.search.MarketPrice.YEARLY_DEPRECIATION), years)
                * Math.pow(1 - com.autox.search.MarketPrice.DEPRECIATION_PER_10K_KM,
                        (listing.mileageKm() - comparable.mileageKm()) / 10_000.0);
    }

    private static int band(int mileageKm) {
        if (mileageKm < 30_000) {
            return 0;
        }
        if (mileageKm < 60_000) {
            return 1;
        }
        if (mileageKm < 100_000) {
            return 2;
        }
        return mileageKm < 150_000 ? 3 : 4;
    }

    private List<DemoCatalogue.Vehicle> generated() {
        return vehicles.subList(DemoCatalogue.VEHICLES.size(), vehicles.size());
    }

    private static DemoCatalogue.Variant variantOf(DemoCatalogue.Vehicle vehicle) {
        return DemoCatalogue.TEMPLATES.stream()
                .filter(template -> template.makeSlug().equals(vehicle.makeSlug())
                        && template.modelSlug().equals(vehicle.modelSlug()))
                .flatMap(template -> template.variants().stream())
                .filter(variant -> variant.version().equals(vehicle.version()))
                .findFirst().orElseThrow(() -> new AssertionError("no version for " + vehicle.title()));
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
