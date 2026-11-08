package com.autox.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.BodyType;
import com.autox.catalog.FuelType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every field of a demo listing agrees with the others, and with the car it names: the
 * description, the body, the doors and seats, the fuel and the power. On every demo listing,
 * the sale ones, the damaged and the leasing offers. No database, no Spring.
 *
 * <p>What it guards against is a listing that a car buyer would catch out: a Golf's text on a
 * 308, "BlueHDi" on a petrol car, a van drawn and sold as a monospace.
 */
class DemoListingConsistencyTest {

    /** A demo listing as it is created: the car and its description. */
    record Seeded(DemoCatalogue.Vehicle vehicle, String description) {
        @Override
        public String toString() {
            return vehicle.title();
        }
    }

    static Stream<Seeded> listings() {
        List<Seeded> all = new ArrayList<>();
        DemoCatalogue.vehicles().forEach(vehicle -> all.add(new Seeded(vehicle, DemoDetails.description(vehicle))));
        DemoCatalogue.DAMAGED.forEach(damaged ->
                all.add(new Seeded(damaged.vehicle(), DemoDetails.damagedDescription(damaged))));
        DemoCatalogue.LEASED.forEach(leased ->
                all.add(new Seeded(leased.vehicle(), DemoDetails.leasedDescription(leased))));
        return all.stream();
    }

    // --- the description -----------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the description names its own make and model, and no other")
    void descriptionNamesItsOwnCar(Seeded listing) {
        DemoCatalogue.Vehicle vehicle = listing.vehicle();
        String text = folded(listing.description());
        DemoCatalogue.Make make = make(vehicle.makeSlug());
        DemoCatalogue.Model model = model(make, vehicle.modelSlug());

        assertThat(text).as("its make").containsPattern(word(shortName(make)));
        assertThat(text).as("its model").containsPattern(word(model.name()));

        for (DemoCatalogue.Make other : DemoCatalogue.MAKES) {
            if (!other.slug().equals(make.slug())) {
                assertThat(text).as("another make").doesNotContainPattern(word(shortName(other)));
            }
            for (DemoCatalogue.Model otherModel : other.models()) {
                boolean own = other.slug().equals(make.slug()) && otherModel.slug().equals(model.slug());
                if (!own) {
                    assertThat(text).as("another model").doesNotContainPattern(word(otherModel.name()));
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the description says it is a demonstration, in words that fit the public site")
    void descriptionFitsThePublicDemo(Seeded listing) {
        assertThat(listing.description())
                .contains("Annonce de démonstration")
                .doesNotContain("développement")
                .doesNotContain("local");
    }

    // --- the car ---------------------------------------------------------------

    /** The body each model is sold with. The exceptions are in {@link #expectedBody}. */
    private static final Map<String, BodyType> BODIES = Map.ofEntries(
            Map.entry("peugeot/208", BodyType.HATCHBACK), Map.entry("peugeot/308", BodyType.HATCHBACK),
            Map.entry("peugeot/3008", BodyType.SUV), Map.entry("peugeot/5008", BodyType.SUV),
            Map.entry("peugeot/partner", BodyType.VAN),
            Map.entry("renault/clio", BodyType.HATCHBACK), Map.entry("renault/captur", BodyType.SUV),
            Map.entry("renault/megane", BodyType.HATCHBACK), Map.entry("renault/scenic", BodyType.MPV),
            Map.entry("renault/kangoo", BodyType.VAN),
            Map.entry("citroen/c3", BodyType.HATCHBACK), Map.entry("citroen/c5-aircross", BodyType.SUV),
            Map.entry("citroen/berlingo", BodyType.MPV), Map.entry("citroen/jumpy", BodyType.VAN),
            Map.entry("volkswagen/golf", BodyType.HATCHBACK), Map.entry("volkswagen/polo", BodyType.HATCHBACK),
            Map.entry("volkswagen/tiguan", BodyType.SUV),
            Map.entry("bmw/serie-3", BodyType.SEDAN), Map.entry("bmw/x1", BodyType.SUV),
            Map.entry("mercedes-benz/classe-a", BodyType.HATCHBACK),
            Map.entry("tesla/model-3", BodyType.SEDAN), Map.entry("tesla/model-y", BodyType.SUV),
            Map.entry("toyota/yaris", BodyType.HATCHBACK), Map.entry("toyota/corolla", BodyType.HATCHBACK),
            Map.entry("dacia/sandero", BodyType.HATCHBACK), Map.entry("dacia/duster", BodyType.SUV),
            Map.entry("fiat/500", BodyType.HATCHBACK));

    private static BodyType expectedBody(DemoCatalogue.Vehicle vehicle) {
        String version = vehicle.version();
        if (version.contains("Estate") || version.contains("Touring Sports")) {
            return BodyType.ESTATE;
        }
        // The first 5008, sold until 2016, was a seven-seat monospace; the next one is an SUV.
        if (vehicle.modelSlug().equals("5008") && vehicle.year() <= 2016) {
            return BodyType.MPV;
        }
        return BODIES.get(vehicle.makeSlug() + "/" + vehicle.modelSlug());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the body is the one the model and version were sold with")
    void bodyMatchesTheModel(Seeded listing) {
        assertThat(listing.vehicle().body()).isEqualTo(expectedBody(listing.vehicle()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("doors and seats are the model's")
    void doorsAndSeats(Seeded listing) {
        DemoCatalogue.Vehicle vehicle = listing.vehicle();
        int doors = DemoDetails.doors(vehicle);
        int seats = DemoDetails.seats(vehicle);
        String key = vehicle.makeSlug() + "/" + vehicle.modelSlug();
        switch (vehicle.body()) {
            case SEDAN -> assertThat(doors).as("a saloon's doors").isEqualTo(4);
            // Two at the front, a sliding one at the side, and the rear doors counted as one.
            case VAN -> {
                assertThat(doors).as("a van's doors").isEqualTo(4);
                assertThat(seats).as("a van's cab").isBetween(2, 3);
            }
            default -> assertThat(doors).as("a hatch, estate, SUV or monospace").isEqualTo(
                    key.equals("fiat/500") ? 3 : 5);
        }
        if (vehicle.body() != BodyType.VAN) {
            int expected = switch (key) {
                case "fiat/500" -> 4;
                case "peugeot/5008" -> 7;
                default -> 5;
            };
            assertThat(seats).as("seats").isEqualTo(expected);
        }
    }

    private static final Pattern DIESEL = Pattern.compile(
            "BlueHDi|\\bdCi\\b|\\bTDI\\b|\\b\\d{2,3}d\\b|sDrive\\d+d|\\bA \\d{3} d\\b");
    private static final Pattern PETROL = Pattern.compile(
            "PureTech|\\bTCe\\b|TSI\\b|\\bSCe\\b|sDrive\\d+i|\\bA \\d{3}\\b(?! [de]\\b)");
    private static final Pattern GAS = Pattern.compile("ECO-G");
    private static final Pattern HYBRID = Pattern.compile(
            "Hybrid|Hybride|E-Tech|eTSI|\\b\\d{2,3}h\\b|\\b\\d{3}e\\b|\\bA \\d{3} e\\b");

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the fuel is the one the version's engine burns")
    void fuelMatchesTheVersion(Seeded listing) {
        DemoCatalogue.Vehicle vehicle = listing.vehicle();
        String version = vehicle.version();
        FuelType fuel = vehicle.fuel();

        boolean diesel = DIESEL.matcher(version).find();
        boolean petrol = PETROL.matcher(version).find();
        boolean gas = GAS.matcher(version).find();
        boolean hybrid = HYBRID.matcher(version).find();

        switch (fuel) {
            case DIESEL -> assertThat(diesel).as("a diesel's version names a diesel engine").isTrue();
            case LPG -> assertThat(gas).as("an LPG car's version says ECO-G").isTrue();
            case HYBRID_PETROL, PLUGIN_HYBRID -> assertThat(hybrid).as("a hybrid's version says so").isTrue();
            case ELECTRIC -> assertThat(diesel || petrol || gas || hybrid).as("an electric car has no engine").isFalse();
            case PETROL -> assertThat(diesel || gas || hybrid).as("a petrol car's version").isFalse();
            default -> { }
        }
        if (diesel) {
            assertThat(fuel).as("a diesel engine").isIn(FuelType.DIESEL, FuelType.HYBRID_DIESEL);
        }
        if (petrol && !hybrid && !gas) {
            assertThat(fuel).as("a petrol engine").isEqualTo(FuelType.PETROL);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the power is the one the version's name states")
    void powerMatchesTheVersion(Seeded listing) {
        // As in DemoCatalogueTest, now for the damaged and leasing offers too.
        Pattern figure = Pattern.compile("(?<![\\w.])(\\d{2,3})h?(?![\\w.])");
        DemoCatalogue.Vehicle vehicle = listing.vehicle();
        List<Integer> stated = figure.matcher(vehicle.version()).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();
        if (!stated.isEmpty()) {
            assertThat(stated).as(vehicle.version()).contains(vehicle.powerHp());
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static DemoCatalogue.Make make(String slug) {
        return DemoCatalogue.MAKES.stream().filter(make -> make.slug().equals(slug)).findFirst().orElseThrow();
    }

    private static DemoCatalogue.Model model(DemoCatalogue.Make make, String slug) {
        return make.models().stream().filter(model -> model.slug().equals(slug)).findFirst().orElseThrow();
    }

    /** "Mercedes" for Mercedes-Benz: how a title names it. */
    private static String shortName(DemoCatalogue.Make make) {
        return make.name().split("-")[0];
    }

    /** A name as a whole word, case and accents aside: "308" is not found in "3008". */
    private static Pattern word(String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(folded(name)) + "(?![\\p{L}\\p{N}])");
    }

    private static String folded(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
