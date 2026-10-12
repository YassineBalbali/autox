package com.autox.demo;

import com.autox.catalog.BodyType;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.ExteriorColor;
import com.autox.listing.VehicleCondition;
import com.autox.user.UserRole;
import java.util.List;

/**
 * What the demo seed contains. Data only — nothing here touches a database.
 *
 * <p>Invented for local development, so the search screen has something to show. Sixty
 * listings: fourteen written by hand, the rest generated from model templates with a
 * fixed seed. Sixty because a page of results is twenty-four — it takes more than two
 * pages before "voir plus" and keyset pagination can be exercised in a browser at all.
 * Chosen to exercise every filter rather than to look like a real dealer's stock: seven
 * fuels, both gearboxes, four body shapes, new and used, eight sellers in a dozen towns,
 * and posting dates spread over ten weeks so the four sort orders give four different
 * lists.
 *
 * <p>The makes and models are a stand-in for the real vehicle catalogue, which is a
 * dataset this project does not have yet. Ten makes is enough to make the make and model
 * filters work; it is not a catalogue and must not be mistaken for the start of one.
 */
final class DemoCatalogue {

    /** Every demo account's address ends with this. {@code .invalid} can never resolve. */
    static final String EMAIL_DOMAIN = "@demo.autox.invalid";

    private DemoCatalogue() {
    }

    record Seller(String key, UserRole role, String displayName, String companyName, String registrationNumber) {
        String email() {
            return key + EMAIL_DOMAIN;
        }
    }

    record Make(String slug, String name, boolean popular, List<Model> models) {
    }

    record Model(String slug, String name) {
    }

    record Vehicle(
            String sellerKey,
            String makeSlug,
            String modelSlug,
            String version,
            String title,
            long priceEuros,
            int year,
            int month,
            int mileageKm,
            FuelType fuel,
            Gearbox gearbox,
            BodyType body,
            int powerHp,
            VehicleCondition condition,
            ExteriorColor colour,
            String city,
            String postalCode,
            int publishedDaysAgo) {
    }

    static final List<Seller> SELLERS = List.of(
            new Seller("garage-bellevue", UserRole.PROFESSIONNEL, "Garage Bellevue",
                    "Garage Bellevue SARL", "00000000000018"),
            new Seller("camille", UserRole.PARTICULIER, "Camille R.", null, null),
            new Seller("thomas", UserRole.PARTICULIER, "Thomas L.", null, null),
            new Seller("auto-atlantique", UserRole.PROFESSIONNEL, "Auto Atlantique",
                    "Auto Atlantique SAS", "00000000000026"),
            new Seller("sud-automobiles", UserRole.PROFESSIONNEL, "Sud Automobiles",
                    "Sud Automobiles SARL", "00000000000034"),
            new Seller("nord-vo", UserRole.PROFESSIONNEL, "Nord VO",
                    "Nord Véhicules d’Occasion SAS", "00000000000042"),
            new Seller("ines", UserRole.PARTICULIER, "Inès M.", null, null),
            new Seller("julien", UserRole.PARTICULIER, "Julien P.", null, null));

    /** The sellers a generated listing may belong to who are private individuals. */
    private static final java.util.Set<String> PRIVATE_SELLERS = java.util.Set.of("ines", "julien");

    /**
     * How many listings a generated private seller is given. Below the API's limit of
     * active listings per private account: the seed respects the rule rather than
     * tripping over it at publish.
     */
    private static final int PRIVATE_SELLER_LISTINGS = 3;

    /**
     * Where each generated listing is sold from. A dealer sells from one or two places;
     * the private sellers are elsewhere, which is what makes the location filter worth
     * trying.
     */
    private record Base(String sellerKey, String city, String postalCode) {
    }

    private static final List<Base> BASES = List.of(
            new Base("auto-atlantique", "Bordeaux", "33000"),
            new Base("sud-automobiles", "Marseille", "13008"),
            new Base("nord-vo", "Lille", "59000"),
            new Base("auto-atlantique", "Mérignac", "33700"),
            new Base("sud-automobiles", "Aix-en-Provence", "13100"),
            new Base("nord-vo", "Roubaix", "59100"),
            new Base("garage-bellevue", "Lyon", "69003"),
            new Base("ines", "Toulouse", "31000"),
            new Base("julien", "Rennes", "35000"));

    /** How many of {@link #BASES}, from the start, are dealers. */
    private static final int DEALER_BASES = 7;

    /** One engine-and-gearbox combination a model is sold with. */
    private record Variant(String version, FuelType fuel, Gearbox gearbox, int powerHp) {
    }

    /** A model, what it costs new, and the variants to pick from. */
    private record Template(String makeSlug, String modelSlug, String name, BodyType body,
                            int newPriceEuros, List<Variant> variants) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("peugeot", "208", "Peugeot 208", BodyType.HATCHBACK, 21_000, List.of(
                    new Variant("PureTech 100 Allure", FuelType.PETROL, Gearbox.MANUAL, 100),
                    new Variant("e-208 136 GT", FuelType.ELECTRIC, Gearbox.AUTOMATIC, 136))),
            new Template("renault", "captur", "Renault Captur", BodyType.SUV, 27_000, List.of(
                    new Variant("TCe 90 Evolution", FuelType.PETROL, Gearbox.MANUAL, 90),
                    new Variant("E-Tech Plug-in 160 Intens", FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, 160))),
            new Template("volkswagen", "polo", "Volkswagen Polo", BodyType.HATCHBACK, 22_000, List.of(
                    new Variant("1.0 TSI 95 Life", FuelType.PETROL, Gearbox.MANUAL, 95),
                    new Variant("1.0 TSI 110 DSG7 Style", FuelType.PETROL, Gearbox.AUTOMATIC, 110))),
            new Template("bmw", "x1", "BMW X1", BodyType.SUV, 44_000, List.of(
                    new Variant("sDrive18i 136 xLine", FuelType.PETROL, Gearbox.AUTOMATIC, 136),
                    new Variant("sDrive18d 150 M Sport", FuelType.DIESEL, Gearbox.AUTOMATIC, 150))),
            new Template("toyota", "yaris", "Toyota Yaris", BodyType.HATCHBACK, 23_000, List.of(
                    new Variant("116h Design", FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, 116))),
            new Template("citroen", "c5-aircross", "Citroën C5 Aircross", BodyType.SUV, 36_000, List.of(
                    new Variant("BlueHDi 130 EAT8 Shine", FuelType.DIESEL, Gearbox.AUTOMATIC, 130),
                    new Variant("Hybrid 225 e-EAT8 Shine Pack", FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, 225))),
            new Template("dacia", "sandero", "Dacia Sandero", BodyType.HATCHBACK, 14_000, List.of(
                    new Variant("TCe 90 Expression", FuelType.PETROL, Gearbox.MANUAL, 90),
                    new Variant("ECO-G 100 Stepway", FuelType.LPG, Gearbox.MANUAL, 100))),
            new Template("peugeot", "308", "Peugeot 308", BodyType.HATCHBACK, 30_000, List.of(
                    new Variant("PureTech 130 EAT8 Allure", FuelType.PETROL, Gearbox.AUTOMATIC, 130),
                    new Variant("BlueHDi 130 Active Pack", FuelType.DIESEL, Gearbox.MANUAL, 130))),
            new Template("tesla", "model-3", "Tesla Model 3", BodyType.SEDAN, 43_000, List.of(
                    new Variant("Propulsion", FuelType.ELECTRIC, Gearbox.AUTOMATIC, 283),
                    new Variant("Performance AWD", FuelType.ELECTRIC, Gearbox.AUTOMATIC, 513))),
            new Template("renault", "megane", "Renault Mégane", BodyType.HATCHBACK, 29_000, List.of(
                    new Variant("TCe 140 Techno", FuelType.PETROL, Gearbox.MANUAL, 140),
                    new Variant("Estate Blue dCi 115 EDC Intens", FuelType.DIESEL, Gearbox.AUTOMATIC, 115))),
            new Template("volkswagen", "tiguan", "Volkswagen Tiguan", BodyType.SUV, 42_000, List.of(
                    new Variant("2.0 TDI 150 DSG7 Elegance", FuelType.DIESEL, Gearbox.AUTOMATIC, 150),
                    new Variant("1.5 TSI 150 Life", FuelType.PETROL, Gearbox.MANUAL, 150))),
            new Template("fiat", "500", "Fiat 500", BodyType.HATCHBACK, 17_500, List.of(
                    new Variant("1.2 69 Lounge", FuelType.PETROL, Gearbox.MANUAL, 69),
                    new Variant("e 118 Icône", FuelType.ELECTRIC, Gearbox.AUTOMATIC, 118))),
            new Template("bmw", "serie-3", "BMW Série 3", BodyType.SEDAN, 48_000, List.of(
                    new Variant("318d 150 Business Design", FuelType.DIESEL, Gearbox.AUTOMATIC, 150),
                    new Variant("330e 292 M Sport", FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, 292))),
            new Template("citroen", "c3", "Citroën C3", BodyType.HATCHBACK, 18_500, List.of(
                    new Variant("PureTech 83 Feel", FuelType.PETROL, Gearbox.MANUAL, 83),
                    new Variant("BlueHDi 100 Shine", FuelType.DIESEL, Gearbox.MANUAL, 100))),
            new Template("mercedes-benz", "classe-a", "Mercedes Classe A", BodyType.HATCHBACK, 38_000, List.of(
                    new Variant("A 180 d 116 Progressive Line", FuelType.DIESEL, Gearbox.AUTOMATIC, 116),
                    new Variant("A 250 e 218 AMG Line", FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, 218))),
            new Template("dacia", "duster", "Dacia Duster", BodyType.SUV, 22_000, List.of(
                    new Variant("Blue dCi 115 4x4 Prestige", FuelType.DIESEL, Gearbox.MANUAL, 115),
                    new Variant("ECO-G 100 Expression", FuelType.LPG, Gearbox.MANUAL, 100))),
            new Template("renault", "clio", "Renault Clio", BodyType.HATCHBACK, 20_000, List.of(
                    new Variant("E-Tech 145 Techno", FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, 145),
                    new Variant("Blue dCi 100 Business", FuelType.DIESEL, Gearbox.MANUAL, 100))),
            new Template("volkswagen", "golf", "Volkswagen Golf", BodyType.HATCHBACK, 32_000, List.of(
                    new Variant("1.5 TSI 130 Life", FuelType.PETROL, Gearbox.MANUAL, 130),
                    new Variant("2.0 TDI 115 Life Plus", FuelType.DIESEL, Gearbox.MANUAL, 115))),
            new Template("toyota", "corolla", "Toyota Corolla", BodyType.ESTATE, 31_000, List.of(
                    new Variant("Touring Sports 122h Dynamic", FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, 122),
                    new Variant("Touring Sports 196h GR Sport", FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, 196))),
            new Template("peugeot", "3008", "Peugeot 3008", BodyType.SUV, 39_000, List.of(
                    new Variant("BlueHDi 130 EAT8 Allure", FuelType.DIESEL, Gearbox.AUTOMATIC, 130),
                    new Variant("PureTech 130 Active", FuelType.PETROL, Gearbox.MANUAL, 130))),
            new Template("tesla", "model-y", "Tesla Model Y", BodyType.SUV, 47_000, List.of(
                    new Variant("Grande Autonomie AWD", FuelType.ELECTRIC, Gearbox.AUTOMATIC, 514))));

    private static final List<String> TITLE_NOTES = List.of(
            "", "", " — première main", ", entretien à jour", "", ", garantie 12 mois",
            " — faible kilométrage", "", ", carnet complet");

    private static final ExteriorColor[] COLOURS = {
        ExteriorColor.GREY, ExteriorColor.WHITE, ExteriorColor.BLACK, ExteriorColor.BLUE,
        ExteriorColor.RED, ExteriorColor.SILVER, ExteriorColor.GREEN, ExteriorColor.BEIGE,
    };

    /** How many listings the full seed has. Enough for three pages of results. */
    static final int SIZE = 60;

    /** The year generated ages are counted back from. Fixed, so the set never drifts. */
    private static final int REFERENCE_YEAR = 2026;

    /**
     * Every demo listing: the hand-written ones, then generated ones up to {@link #SIZE}.
     *
     * <p>Generated from a fixed seed, so the set is the same on every machine and on every
     * run — two developers looking at the search page are looking at the same sixty cars,
     * and a screenshot in a bug report can be reproduced. The values are plausible rather
     * than random: mileage follows age, and price follows both.
     */
    static List<Vehicle> vehicles() {
        List<Vehicle> all = new java.util.ArrayList<>(VEHICLES);
        java.util.Random random = new java.util.Random(20_261_012L);
        java.util.Map<String, Integer> generatedFor = new java.util.HashMap<>();

        for (int index = 0; all.size() < SIZE; index++) {
            Template template = TEMPLATES.get(index % TEMPLATES.size());
            // Variants in turn, not at random: each pass through the templates takes the
            // next one, so every fuel and gearbox the templates offer is certain to appear.
            // Left to chance, one fuel type came out with no listing and its filter was dead.
            Variant variant = template.variants().get(
                    (index / TEMPLATES.size()) % template.variants().size());

            Base base = BASES.get(index % BASES.size());
            if (PRIVATE_SELLERS.contains(base.sellerKey())
                    && generatedFor.getOrDefault(base.sellerKey(), 0) >= PRIVATE_SELLER_LISTINGS) {
                base = BASES.get(index % DEALER_BASES);
            }
            generatedFor.merge(base.sellerKey(), 1, Integer::sum);

            int age = 1 + random.nextInt(9);
            int mileage = roundTo(age * (9_000 + random.nextInt(9_000)), 100);
            // About 14 % a year off the remaining value, less a little more for mileage.
            double value = template.newPriceEuros() * Math.pow(0.86, age)
                    * (1 - Math.min(0.15, mileage / 2_000_000.0));
            long price = Math.max(3_500, roundTo((int) value, 500) - 10);

            all.add(new Vehicle(
                    base.sellerKey(), template.makeSlug(), template.modelSlug(), variant.version(),
                    template.name() + " " + variant.version()
                            + TITLE_NOTES.get(random.nextInt(TITLE_NOTES.size())),
                    price, REFERENCE_YEAR - age, 1 + random.nextInt(12), mileage,
                    variant.fuel(), variant.gearbox(), template.body(), variant.powerHp(),
                    VehicleCondition.USED, COLOURS[random.nextInt(COLOURS.length)],
                    base.city(), base.postalCode(), random.nextInt(75)));
        }
        return List.copyOf(all);
    }

    private static int roundTo(int value, int step) {
        return Math.max(step, Math.round(value / (float) step) * step);
    }

    static final List<Make> MAKES = List.of(
            new Make("peugeot", "Peugeot", true, List.of(
                    new Model("208", "208"), new Model("308", "308"), new Model("3008", "3008"))),
            new Make("renault", "Renault", true, List.of(
                    new Model("clio", "Clio"), new Model("captur", "Captur"), new Model("megane", "Mégane"))),
            new Make("citroen", "Citroën", true, List.of(
                    new Model("c3", "C3"), new Model("c5-aircross", "C5 Aircross"))),
            new Make("volkswagen", "Volkswagen", true, List.of(
                    new Model("golf", "Golf"), new Model("polo", "Polo"), new Model("tiguan", "Tiguan"))),
            new Make("bmw", "BMW", false, List.of(
                    new Model("serie-3", "Série 3"), new Model("x1", "X1"))),
            new Make("mercedes-benz", "Mercedes-Benz", false, List.of(
                    new Model("classe-a", "Classe A"))),
            new Make("tesla", "Tesla", false, List.of(
                    new Model("model-3", "Model 3"), new Model("model-y", "Model Y"))),
            new Make("toyota", "Toyota", false, List.of(
                    new Model("yaris", "Yaris"), new Model("corolla", "Corolla"))),
            new Make("dacia", "Dacia", false, List.of(
                    new Model("sandero", "Sandero"), new Model("duster", "Duster"))),
            new Make("fiat", "Fiat", false, List.of(
                    new Model("500", "500"))));

    static final List<Vehicle> VEHICLES = List.of(
            new Vehicle("garage-bellevue", "peugeot", "308", "1.5 BlueHDi 130 Allure Pack",
                    "Peugeot 308 BlueHDi 130 Allure Pack — première main", 18_990, 2021, 3, 62_400,
                    FuelType.DIESEL, Gearbox.MANUAL, BodyType.HATCHBACK, 130, VehicleCondition.USED,
                    ExteriorColor.GREY, "Lyon", "69003", 1),
            new Vehicle("garage-bellevue", "renault", "clio", "1.0 TCe 90 Evolution",
                    "Renault Clio V TCe 90 Evolution", 13_490, 2022, 6, 38_150,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.HATCHBACK, 90, VehicleCondition.USED,
                    ExteriorColor.RED, "Lyon", "69003", 2),
            new Vehicle("garage-bellevue", "volkswagen", "golf", "2.0 TDI 150 DSG7 Style",
                    "Volkswagen Golf VIII TDI 150 DSG Style", 26_490, 2020, 11, 104_300,
                    FuelType.DIESEL, Gearbox.AUTOMATIC, BodyType.HATCHBACK, 150, VehicleCondition.USED,
                    ExteriorColor.BLACK, "Villeurbanne", "69100", 4),
            new Vehicle("garage-bellevue", "tesla", "model-3", "Grande Autonomie AWD",
                    "Tesla Model 3 Grande Autonomie — TVA récupérable", 31_990, 2022, 2, 47_800,
                    FuelType.ELECTRIC, Gearbox.AUTOMATIC, BodyType.SEDAN, 440, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Lyon", "69007", 6),
            new Vehicle("garage-bellevue", "peugeot", "3008", "Hybrid 225 e-EAT8 GT",
                    "Peugeot 3008 Hybrid 225 GT", 34_900, 2023, 1, 21_600,
                    FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, BodyType.SUV, 225, VehicleCondition.USED,
                    ExteriorColor.BLUE, "Lyon", "69003", 9),
            new Vehicle("garage-bellevue", "dacia", "duster", "TCe 130 Journey",
                    "Dacia Duster TCe 130 Journey — neuf, disponible", 23_500, 2026, 9, 12,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.SUV, 130, VehicleCondition.NEW,
                    ExteriorColor.GREEN, "Lyon", "69003", 12),
            new Vehicle("garage-bellevue", "toyota", "corolla", "Touring Sports 140h Dynamic",
                    "Toyota Corolla Touring Sports Hybride 140h", 24_800, 2022, 9, 55_200,
                    FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, BodyType.ESTATE, 140, VehicleCondition.USED,
                    ExteriorColor.SILVER, "Bron", "69500", 17),
            new Vehicle("garage-bellevue", "bmw", "serie-3", "320d xDrive 190 M Sport",
                    "BMW Série 3 320d xDrive M Sport", 36_750, 2021, 5, 78_900,
                    FuelType.DIESEL, Gearbox.AUTOMATIC, BodyType.SEDAN, 190, VehicleCondition.USED,
                    ExteriorColor.BLUE, "Lyon", "69006", 23),
            new Vehicle("garage-bellevue", "mercedes-benz", "classe-a", "A 200 163 AMG Line",
                    "Mercedes Classe A 200 AMG Line", 29_900, 2021, 10, 49_300,
                    FuelType.PETROL, Gearbox.AUTOMATIC, BodyType.HATCHBACK, 163, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Lyon", "69002", 31),
            new Vehicle("garage-bellevue", "tesla", "model-y", "Propulsion",
                    "Tesla Model Y Propulsion — neuve", 48_990, 2026, 8, 25,
                    FuelType.ELECTRIC, Gearbox.AUTOMATIC, BodyType.SUV, 299, VehicleCondition.NEW,
                    ExteriorColor.GREY, "Lyon", "69007", 38),
            new Vehicle("camille", "fiat", "500", "1.0 Hybrid 70 Dolcevita",
                    "Fiat 500 Hybrid Dolcevita, toit panoramique", 11_900, 2021, 4, 29_700,
                    FuelType.HYBRID_PETROL, Gearbox.MANUAL, BodyType.HATCHBACK, 70, VehicleCondition.USED,
                    ExteriorColor.BEIGE, "Nantes", "44000", 3),
            new Vehicle("camille", "citroen", "c3", "PureTech 83 Shine",
                    "Citroën C3 PureTech 83 Shine, entretien à jour", 9_800, 2019, 7, 71_500,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.HATCHBACK, 83, VehicleCondition.USED,
                    ExteriorColor.ORANGE, "Nantes", "44300", 14),
            new Vehicle("thomas", "renault", "megane", "Estate 1.5 Blue dCi 115 Business",
                    "Renault Mégane Estate dCi 115 Business", 12_300, 2019, 2, 142_000,
                    FuelType.DIESEL, Gearbox.MANUAL, BodyType.ESTATE, 115, VehicleCondition.USED,
                    ExteriorColor.GREY, "Strasbourg", "67000", 8),
            new Vehicle("thomas", "dacia", "sandero", "SCe 75 Essentiel",
                    "Dacia Sandero SCe 75, faible kilométrage", 7_200, 2018, 12, 58_400,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.HATCHBACK, 75, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Strasbourg", "67200", 52));
}
