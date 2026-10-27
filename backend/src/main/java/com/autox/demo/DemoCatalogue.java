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
 * <p>Invented, so the site has something to show. A hundred and fifty-two listings: sixteen
 * written by hand, the rest generated from model templates with a fixed seed. Enough for
 * seven pages of results, and enough of the eight most common models that their listings
 * have the comparables the price indicator needs. Chosen to exercise every filter: seven
 * fuels, both gearboxes, six body shapes, new and used, ten sellers in fifteen towns,
 * and posting dates spread over three months so the four sort orders give four different
 * lists.
 *
 * <p><b>Plausible to someone who knows cars.</b> Every version is one that was sold in
 * France, in the years it was sold, with the power its name states; prices follow the new
 * price, the age and the mileage. The site is judged on details like these.
 *
 * <p>The makes and models are a stand-in for the real vehicle catalogue, which is a
 * dataset this project does not have yet. Ten makes with listings make the make and model
 * filters work, and eight more without any ({@link #CATALOGUE_ONLY}) give a seller the
 * other common makes to choose from; it is not a catalogue and must not be mistaken for
 * the start of one.
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
            new Seller("julien", UserRole.PARTICULIER, "Julien P.", null, null),
            new Seller("sarah", UserRole.PARTICULIER, "Sarah B.", null, null),
            new Seller("karim", UserRole.PARTICULIER, "Karim D.", null, null));

    /** The sellers a generated listing may belong to who are private individuals. */
    private static final java.util.Set<String> PRIVATE_SELLERS = java.util.Set.of("ines", "julien", "sarah", "karim");

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
            new Base("julien", "Rennes", "35000"),
            new Base("sarah", "Montpellier", "34000"),
            new Base("karim", "Grenoble", "38000"));

    /** How many of {@link #BASES}, from the start, are dealers. */
    private static final int DEALER_BASES = 7;

    /**
     * One engine, gearbox and trim a model was sold with in France, and when.
     *
     * <p>{@code firstYear} to {@code lastYear} are the years this exact version was on sale; a
     * generated listing is never registered outside them. The power is the one the version's
     * name states ("TCe 90" is 90 ch), and the new price is roughly what it listed for — the
     * starting point of its depreciation. {@code share} is how many of the model's listings
     * it gets for each one another version of {@code share} 1 gets.
     */
    record Variant(String version, FuelType fuel, Gearbox gearbox, int powerHp,
                   int newPriceEuros, int firstYear, int lastYear, int share) {
    }

    /**
     * A model and the versions to pick from. {@code weight} is how many listings it is
     * given: the volume models get about ten each, so that most of their listings have the
     * five comparables the price indicator needs, and the rest a few, which shows the
     * indicator staying silent when there is too little to compare with.
     */
    record Template(String makeSlug, String modelSlug, String name, BodyType body,
                    int weight, List<Variant> variants) {
    }

    private static Variant v(String version, FuelType fuel, Gearbox gearbox, int powerHp,
                             int newPriceEuros, int firstYear, int lastYear) {
        return v(1, version, fuel, gearbox, powerHp, newPriceEuros, firstYear, lastYear);
    }

    private static Variant v(int share, String version, FuelType fuel, Gearbox gearbox, int powerHp,
                             int newPriceEuros, int firstYear, int lastYear) {
        return new Variant(version, fuel, gearbox, powerHp, newPriceEuros, firstYear, lastYear, share);
    }

    private static final Gearbox MANUAL = Gearbox.MANUAL;
    private static final Gearbox AUTO = Gearbox.AUTOMATIC;

    static final List<Template> TEMPLATES = List.of(
            // ---- the volume models: about ten listings each, hand-written ones included,
            // most of them one version — the one that sells — so that version has the
            // comparables the price indicator needs, which are counted per version
            new Template("renault", "clio", "Renault Clio", BodyType.HATCHBACK, 9, List.of(
                    v(3, "TCe 90 Evolution", FuelType.PETROL, MANUAL, 90, 19_500, 2022, 2025),
                    v(1, "E-Tech 145 Techno", FuelType.HYBRID_PETROL, AUTO, 145, 25_500, 2022, 2025))),
            new Template("peugeot", "208", "Peugeot 208", BodyType.HATCHBACK, 10, List.of(
                    v(4, "PureTech 100 Allure", FuelType.PETROL, MANUAL, 100, 22_500, 2020, 2025),
                    v(1, "e-208 136 GT", FuelType.ELECTRIC, AUTO, 136, 39_000, 2020, 2022))),
            new Template("peugeot", "308", "Peugeot 308", BodyType.HATCHBACK, 10, List.of(
                    v("PureTech 130 EAT8 Allure", FuelType.PETROL, AUTO, 130, 33_500, 2022, 2025))),
            new Template("peugeot", "3008", "Peugeot 3008", BodyType.SUV, 10, List.of(
                    v("BlueHDi 130 EAT8 Allure", FuelType.DIESEL, AUTO, 130, 38_500, 2020, 2023))),
            new Template("volkswagen", "golf", "Volkswagen Golf", BodyType.HATCHBACK, 10, List.of(
                    v("1.5 TSI 130 Life", FuelType.PETROL, MANUAL, 130, 30_500, 2020, 2024))),
            new Template("renault", "captur", "Renault Captur", BodyType.SUV, 10, List.of(
                    v(4, "TCe 90 Evolution", FuelType.PETROL, MANUAL, 90, 24_500, 2022, 2025),
                    v(1, "E-Tech 145 Techno", FuelType.HYBRID_PETROL, AUTO, 145, 30_000, 2022, 2025))),
            new Template("tesla", "model-3", "Tesla Model 3", BodyType.SEDAN, 9, List.of(
                    v(3, "Propulsion", FuelType.ELECTRIC, AUTO, 283, 44_000, 2021, 2025),
                    v(1, "Grande Autonomie AWD", FuelType.ELECTRIC, AUTO, 440, 52_000, 2021, 2023))),
            new Template("dacia", "sandero", "Dacia Sandero", BodyType.HATCHBACK, 10, List.of(
                    v(3, "Stepway ECO-G 100 Expression", FuelType.LPG, MANUAL, 100, 17_500, 2021, 2025),
                    v(1, "TCe 90 Expression", FuelType.PETROL, MANUAL, 90, 15_500, 2021, 2025))),
            // ---- the rest: a few each
            new Template("volkswagen", "tiguan", "Volkswagen Tiguan", BodyType.SUV, 4, List.of(
                    v("2.0 TDI 150 DSG7 Elegance", FuelType.DIESEL, AUTO, 150, 46_000, 2021, 2023),
                    v("1.5 TSI 150 Life", FuelType.PETROL, MANUAL, 150, 37_500, 2021, 2023))),
            new Template("citroen", "c5-aircross", "Citroën C5 Aircross", BodyType.SUV, 4, List.of(
                    v("BlueHDi 130 EAT8 Shine", FuelType.DIESEL, AUTO, 130, 37_000, 2019, 2025),
                    v("Hybrid 225 e-EAT8 Shine", FuelType.PLUGIN_HYBRID, AUTO, 225, 45_000, 2020, 2024))),
            new Template("tesla", "model-y", "Tesla Model Y", BodyType.SUV, 3, List.of(
                    v("Grande Autonomie AWD", FuelType.ELECTRIC, AUTO, 514, 52_000, 2021, 2025),
                    v("Propulsion", FuelType.ELECTRIC, AUTO, 299, 46_000, 2022, 2025))),
            new Template("toyota", "yaris", "Toyota Yaris", BodyType.HATCHBACK, 3, List.of(
                    v("116h Design", FuelType.HYBRID_PETROL, AUTO, 116, 23_500, 2020, 2023))),
            new Template("volkswagen", "polo", "Volkswagen Polo", BodyType.HATCHBACK, 2, List.of(
                    v("1.0 TSI 95 Life", FuelType.PETROL, MANUAL, 95, 21_500, 2021, 2025),
                    v("1.0 TSI 110 DSG7 Style", FuelType.PETROL, AUTO, 110, 26_000, 2021, 2025))),
            new Template("bmw", "x1", "BMW X1", BodyType.SUV, 2, List.of(
                    v("sDrive18i 136 xLine", FuelType.PETROL, AUTO, 136, 45_500, 2023, 2025),
                    v("sDrive18d 150 M Sport", FuelType.DIESEL, AUTO, 150, 50_000, 2023, 2025))),
            new Template("bmw", "serie-3", "BMW Série 3", BodyType.SEDAN, 2, List.of(
                    v("318d 150 Business Design", FuelType.DIESEL, AUTO, 150, 43_000, 2019, 2024),
                    v("330e 292 M Sport", FuelType.PLUGIN_HYBRID, AUTO, 292, 56_000, 2020, 2025))),
            new Template("mercedes-benz", "classe-a", "Mercedes Classe A", BodyType.HATCHBACK, 2, List.of(
                    v("A 180 d 116 Progressive Line", FuelType.DIESEL, AUTO, 116, 36_000, 2019, 2024),
                    v("A 250 e 218 AMG Line", FuelType.PLUGIN_HYBRID, AUTO, 218, 47_000, 2020, 2025))),
            new Template("renault", "megane", "Renault Mégane", BodyType.HATCHBACK, 1, List.of(
                    v("TCe 140 Techno", FuelType.PETROL, MANUAL, 140, 30_000, 2022, 2023),
                    v("Estate Blue dCi 115 EDC Intens", FuelType.DIESEL, AUTO, 115, 31_000, 2019, 2021))),
            new Template("citroen", "c3", "Citroën C3", BodyType.HATCHBACK, 1, List.of(
                    v("PureTech 83 Feel", FuelType.PETROL, MANUAL, 83, 16_500, 2017, 2023),
                    v("BlueHDi 100 Shine", FuelType.DIESEL, MANUAL, 100, 20_500, 2017, 2023))),
            new Template("dacia", "duster", "Dacia Duster", BodyType.SUV, 2, List.of(
                    v("Blue dCi 115 4x4 Prestige", FuelType.DIESEL, MANUAL, 115, 25_000, 2018, 2023),
                    v("ECO-G 100 Expression", FuelType.LPG, MANUAL, 100, 19_500, 2022, 2025))),
            new Template("toyota", "corolla", "Toyota Corolla", BodyType.ESTATE, 1, List.of(
                    v("Touring Sports 122h Dynamic", FuelType.HYBRID_PETROL, AUTO, 122, 31_000, 2019, 2022),
                    v("Touring Sports 196h GR Sport", FuelType.HYBRID_PETROL, AUTO, 196, 39_000, 2023, 2025))),
            new Template("fiat", "500", "Fiat 500", BodyType.HATCHBACK, 1, List.of(
                    v("1.2 69 Lounge", FuelType.PETROL, MANUAL, 69, 15_500, 2015, 2019),
                    v("e 118 Icône", FuelType.ELECTRIC, AUTO, 118, 33_000, 2021, 2025))),
            // ---- monospaces and vans, so that no body type on the home page is empty. Last
            // in the list, after the models above; one of each kind has enough listings of
            // one version for the price indicator. The passenger Berlingo is the monospace;
            // the van-only Partner and Jumpy are sold as "fourgon".
            new Template("citroen", "berlingo", "Citroën Berlingo", BodyType.MPV, 10, List.of(
                    v(4, "BlueHDi 100 Feel", FuelType.DIESEL, MANUAL, 100, 25_500, 2019, 2022),
                    v(1, "PureTech 110 Shine", FuelType.PETROL, MANUAL, 110, 26_500, 2019, 2022))),
            new Template("renault", "scenic", "Renault Scénic", BodyType.MPV, 4, List.of(
                    v("Blue dCi 120 Intens", FuelType.DIESEL, MANUAL, 120, 31_500, 2018, 2022),
                    v("TCe 140 EDC Business", FuelType.PETROL, AUTO, 140, 31_000, 2019, 2022))),
            new Template("renault", "kangoo", "Renault Kangoo", BodyType.VAN, 10, List.of(
                    v(4, "Van Blue dCi 95 Grand Confort", FuelType.DIESEL, MANUAL, 95, 27_000, 2021, 2025),
                    v(1, "Van Blue dCi 95 Extra", FuelType.DIESEL, MANUAL, 95, 29_500, 2021, 2025))),
            new Template("citroen", "jumpy", "Citroën Jumpy", BodyType.VAN, 3, List.of(
                    v("Fourgon M BlueHDi 120 Club", FuelType.DIESEL, MANUAL, 120, 34_000, 2019, 2023),
                    v("Fourgon M BlueHDi 145 EAT8 Driver", FuelType.DIESEL, AUTO, 145, 41_000, 2019, 2023))),
            new Template("peugeot", "partner", "Peugeot Partner", BodyType.VAN, 3, List.of(
                    v("Fourgon Standard BlueHDi 100 Premium", FuelType.DIESEL, MANUAL, 100, 25_500, 2019, 2023),
                    v("Fourgon Long BlueHDi 130 EAT8 Asphalt", FuelType.DIESEL, AUTO, 130, 32_500, 2019, 2023))));

    /**
     * The first year each model was sold in France under its name. Every listing, hand-written
     * or generated, is registered in or after it: a 2017 Model Y is the kind of thing anyone
     * who knows cars notices at once.
     */
    static final java.util.Map<String, Integer> LAUNCH_YEARS = java.util.Map.ofEntries(
            java.util.Map.entry("peugeot/208", 2012), java.util.Map.entry("peugeot/308", 2007),
            java.util.Map.entry("peugeot/3008", 2009), java.util.Map.entry("renault/clio", 1990),
            java.util.Map.entry("renault/captur", 2013), java.util.Map.entry("renault/megane", 1995),
            java.util.Map.entry("citroen/c3", 2002), java.util.Map.entry("citroen/c5-aircross", 2019),
            java.util.Map.entry("volkswagen/golf", 1974), java.util.Map.entry("volkswagen/polo", 1975),
            java.util.Map.entry("volkswagen/tiguan", 2007), java.util.Map.entry("bmw/serie-3", 1975),
            java.util.Map.entry("bmw/x1", 2009), java.util.Map.entry("mercedes-benz/classe-a", 1997),
            java.util.Map.entry("tesla/model-3", 2019), java.util.Map.entry("tesla/model-y", 2021),
            java.util.Map.entry("toyota/yaris", 1999), java.util.Map.entry("toyota/corolla", 1966),
            java.util.Map.entry("dacia/sandero", 2008), java.util.Map.entry("dacia/duster", 2010),
            java.util.Map.entry("fiat/500", 2007), java.util.Map.entry("peugeot/5008", 2009),
            java.util.Map.entry("peugeot/partner", 1996), java.util.Map.entry("renault/scenic", 1996),
            java.util.Map.entry("renault/kangoo", 1997), java.util.Map.entry("citroen/berlingo", 1996),
            java.util.Map.entry("citroen/jumpy", 1994));

    private static final String LOW_MILEAGE = " — faible kilométrage";

    private static final List<String> TITLE_NOTES = List.of(
            "", "", " — première main", ", entretien à jour", "", ", garantie 12 mois",
            LOW_MILEAGE, "", ", carnet complet");

    private static final ExteriorColor[] COLOURS = {
        ExteriorColor.GREY, ExteriorColor.WHITE, ExteriorColor.BLACK, ExteriorColor.BLUE,
        ExteriorColor.RED, ExteriorColor.SILVER, ExteriorColor.GREEN, ExteriorColor.BEIGE,
    };

    /**
     * How far a seller's asking price strays from what the car is worth: a few listed
     * keenly, a few hopefully, most near the value. Without the spread every car would be
     * priced at its value and the price indicator would call them all "Prix du marché".
     */
    static final double[] ASKING = {0.84, 0.90, 0.95, 0.98, 1.0, 1.02, 1.05, 1.10, 1.16};

    /** How many listings the full seed has. */
    static final int SIZE = 152;

    /** The date ages are counted back from: October 2026. Fixed, so the set never drifts. */
    static final int REFERENCE_YEAR = 2026;
    private static final int REFERENCE_MONTH = 10;

    /** Kilometres a year an average car in France covers. */
    private static final int KM_PER_YEAR = 13_000;

    /**
     * Every demo listing: the hand-written ones, then generated ones up to {@link #SIZE}.
     *
     * <p>Generated from a fixed seed, so the set is the same on every machine and on every
     * run. The values follow the market rather than chance:
     * <ul>
     *   <li>the year is one the version was sold in, never before the model existed;</li>
     *   <li>mileage follows age, at 10 000 to 17 000 km a year;</li>
     *   <li>the value is the new price less depreciation — a fifth in the first year, then
     *       11 % a year, 15 % for an electric car, whose prices have fallen faster — adjusted
     *       for mileage above or below the average, and the asking price strays from it as
     *       sellers' prices do.</li>
     * </ul>
     */
    static List<Vehicle> vehicles() {
        List<Vehicle> all = new java.util.ArrayList<>(VEHICLES);
        java.util.Map<String, Integer> generatedFor = new java.util.HashMap<>();
        java.util.Map<Template, Integer> taken = new java.util.HashMap<>();

        List<Template> order = interleaved();
        for (int index = 0; all.size() < SIZE && index < order.size(); index++) {
            Template template = order.get(index);
            // Versions in turn, not at random, so every fuel and gearbox a model offers is
            // certain to appear: left to chance, one fuel came out with no listing and its
            // filter was dead.
            int turn = taken.merge(template, 1, Integer::sum) - 1;
            List<Variant> turns = template.variants().stream()
                    .flatMap(variant -> java.util.Collections.nCopies(variant.share(), variant).stream())
                    .toList();
            Variant variant = turns.get(turn % turns.size());
            // A generator per listing, seeded by the model and the listing's place in it, not
            // one for the whole set: then adding a listing to one model leaves every other
            // model's cars as they were, instead of redrawing the lot.
            java.util.Random random = new java.util.Random(java.util.Objects.hash(
                    20_261_020L, template.makeSlug(), template.modelSlug(), turn));

            Base base = BASES.get(index % BASES.size());
            if (PRIVATE_SELLERS.contains(base.sellerKey())
                    && generatedFor.getOrDefault(base.sellerKey(), 0) >= PRIVATE_SELLER_LISTINGS) {
                base = BASES.get(index % DEALER_BASES);
            }
            generatedFor.merge(base.sellerKey(), 1, Integer::sum);

            int lastYear = Math.min(variant.lastYear(), REFERENCE_YEAR - 1);
            // Two draws averaged: the years in the middle of a version's life are the
            // commonest on the used market, as they are here.
            int span = lastYear - variant.firstYear();
            int year = variant.firstYear()
                    + (int) Math.round((random.nextDouble() + random.nextDouble()) / 2 * span);
            int month = 1 + random.nextInt(12);
            double age = ageInYears(year, month);
            int mileage = Math.max(1_500, roundTo((int) (age * (10_000 + random.nextInt(7_000))), 100));
            double asking = ASKING[random.nextInt(ASKING.length)];
            long price = askingPrice(variant.newPriceEuros(), variant.fuel(), age, mileage, asking);
            String note = TITLE_NOTES.get(random.nextInt(TITLE_NOTES.size()));
            if (note.equals(LOW_MILEAGE) && mileage > 0.8 * age * KM_PER_YEAR) {
                // Only where it is true: "faible kilométrage" on an average mileage is the
                // kind of claim a buyer checks, and the demo would be caught out.
                note = "";
            }

            all.add(new Vehicle(
                    base.sellerKey(), template.makeSlug(), template.modelSlug(), variant.version(),
                    template.name() + " " + variant.version() + note,
                    price, year, month, mileage,
                    variant.fuel(), variant.gearbox(), template.body(), variant.powerHp(),
                    VehicleCondition.USED, COLOURS[random.nextInt(COLOURS.length)],
                    base.city(), base.postalCode(), random.nextInt(90)));
        }
        return List.copyOf(all);
    }

    /**
     * The templates repeated by weight and dealt round-robin, so a model's listings are
     * spread through the posting dates and the sellers instead of arriving in a block.
     */
    private static List<Template> interleaved() {
        List<Template> order = new java.util.ArrayList<>();
        int rounds = TEMPLATES.stream().mapToInt(Template::weight).max().orElse(0);
        for (int round = 0; round < rounds; round++) {
            for (Template template : TEMPLATES) {
                if (round < template.weight()) {
                    order.add(template);
                }
            }
        }
        return order;
    }

    /** Years from first registration to the reference date. */
    static double ageInYears(int year, int month) {
        return (REFERENCE_YEAR + (REFERENCE_MONTH - 1) / 12.0) - (year + (month - 1) / 12.0);
    }

    /**
     * What a seller would ask: the value after depreciation and mileage, times how keen
     * they are, ending in 90 as dealers' prices do.
     */
    static long askingPrice(int newPriceEuros, FuelType fuel, double age, int mileageKm, double asking) {
        double yearly = fuel == FuelType.ELECTRIC ? 0.85 : 0.89;
        double retained = age <= 1 ? 1 - 0.20 * age : 0.80 * Math.pow(yearly, age - 1);
        // 1.5 % per 10 000 km off the average for the age, within reason either way.
        double extraKm = mileageKm - age * KM_PER_YEAR;
        double forMileage = Math.max(0.80, Math.min(1.08, 1 - 0.015 * extraKm / 10_000));
        double value = newPriceEuros * retained * forMileage * asking;
        return Math.max(3_490, roundTo((int) value, 100) - 10);
    }

    private static int roundTo(int value, int step) {
        return Math.max(step, Math.round(value / (float) step) * step);
    }

    /**
     * Makes in the catalogue with no demo listing: the rest of the obvious volume makes on the
     * French used market (Audi sells about as many used cars as BMW), so that a seller can
     * choose them, and their models, from the list rather than typing them. The catalogue and
     * the listings are separate concerns; these exist for the first and stay out of the second.
     */
    static final List<Make> CATALOGUE_ONLY = List.of(
            new Make("audi", "Audi", false, List.of(
                    new Model("a1", "A1"), new Model("a3", "A3"), new Model("a4", "A4"),
                    new Model("a5", "A5"), new Model("a6", "A6"), new Model("q2", "Q2"),
                    new Model("q3", "Q3"), new Model("q5", "Q5"))),
            new Make("ford", "Ford", false, List.of(
                    new Model("fiesta", "Fiesta"), new Model("focus", "Focus"), new Model("puma", "Puma"),
                    new Model("kuga", "Kuga"), new Model("transit-custom", "Transit Custom"))),
            new Make("opel", "Opel", false, List.of(
                    new Model("corsa", "Corsa"), new Model("astra", "Astra"), new Model("mokka", "Mokka"),
                    new Model("crossland", "Crossland"), new Model("grandland", "Grandland"))),
            new Make("nissan", "Nissan", false, List.of(
                    new Model("micra", "Micra"), new Model("juke", "Juke"), new Model("qashqai", "Qashqai"),
                    new Model("x-trail", "X-Trail"), new Model("leaf", "Leaf"))),
            new Make("kia", "Kia", false, List.of(
                    new Model("picanto", "Picanto"), new Model("rio", "Rio"), new Model("ceed", "Ceed"),
                    new Model("niro", "Niro"), new Model("sportage", "Sportage"))),
            new Make("hyundai", "Hyundai", false, List.of(
                    new Model("i10", "i10"), new Model("i20", "i20"), new Model("i30", "i30"),
                    new Model("kona", "Kona"), new Model("tucson", "Tucson"))),
            new Make("skoda", "Škoda", false, List.of(
                    new Model("fabia", "Fabia"), new Model("octavia", "Octavia"), new Model("kamiq", "Kamiq"),
                    new Model("karoq", "Karoq"), new Model("kodiaq", "Kodiaq"))),
            new Make("seat", "Seat", false, List.of(
                    new Model("ibiza", "Ibiza"), new Model("leon", "Leon"), new Model("arona", "Arona"),
                    new Model("ateca", "Ateca"))));

    /** The makes the demo listings are of; each has at least one. */
    static final List<Make> LISTED_MAKES = List.of(
            new Make("peugeot", "Peugeot", true, List.of(
                    new Model("208", "208"), new Model("308", "308"), new Model("3008", "3008"),
                    new Model("5008", "5008"), new Model("partner", "Partner"))),
            new Make("renault", "Renault", true, List.of(
                    new Model("clio", "Clio"), new Model("captur", "Captur"), new Model("megane", "Mégane"),
                    new Model("scenic", "Scénic"), new Model("kangoo", "Kangoo"))),
            new Make("citroen", "Citroën", true, List.of(
                    new Model("c3", "C3"), new Model("c5-aircross", "C5 Aircross"),
                    new Model("berlingo", "Berlingo"), new Model("jumpy", "Jumpy"))),
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

    /**
     * Every make the seed creates: those its listings use, then {@link #CATALOGUE_ONLY}.
     * After both, so that neither is still null when it is built.
     */
    static final List<Make> MAKES = java.util.stream.Stream.concat(
            LISTED_MAKES.stream(), CATALOGUE_ONLY.stream()).toList();

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
                    "Volkswagen Golf VIII TDI 150 DSG Style", 20_490, 2020, 11, 104_300,
                    FuelType.DIESEL, Gearbox.AUTOMATIC, BodyType.HATCHBACK, 150, VehicleCondition.USED,
                    ExteriorColor.BLACK, "Villeurbanne", "69100", 4),
            new Vehicle("garage-bellevue", "tesla", "model-3", "Grande Autonomie AWD",
                    "Tesla Model 3 Grande Autonomie — TVA récupérable", 27_990, 2022, 2, 47_800,
                    FuelType.ELECTRIC, Gearbox.AUTOMATIC, BodyType.SEDAN, 440, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Lyon", "69007", 6),
            new Vehicle("garage-bellevue", "peugeot", "3008", "Hybrid 225 e-EAT8 GT",
                    "Peugeot 3008 Hybrid 225 GT", 29_900, 2023, 1, 21_600,
                    FuelType.PLUGIN_HYBRID, Gearbox.AUTOMATIC, BodyType.SUV, 225, VehicleCondition.USED,
                    ExteriorColor.BLUE, "Lyon", "69003", 9),
            new Vehicle("garage-bellevue", "dacia", "duster", "TCe 130 Journey",
                    "Dacia Duster TCe 130 Journey — neuf, disponible", 23_500, 2026, 9, 12,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.SUV, 130, VehicleCondition.NEW,
                    ExteriorColor.GREEN, "Lyon", "69003", 12),
            new Vehicle("garage-bellevue", "toyota", "corolla", "Touring Sports 140h Dynamic",
                    "Toyota Corolla Touring Sports Hybride 140h", 23_900, 2023, 3, 41_200,
                    FuelType.HYBRID_PETROL, Gearbox.AUTOMATIC, BodyType.ESTATE, 140, VehicleCondition.USED,
                    ExteriorColor.SILVER, "Bron", "69500", 17),
            new Vehicle("garage-bellevue", "bmw", "serie-3", "320d xDrive 190 M Sport",
                    "BMW Série 3 320d xDrive M Sport", 29_900, 2021, 5, 78_900,
                    FuelType.DIESEL, Gearbox.AUTOMATIC, BodyType.SEDAN, 190, VehicleCondition.USED,
                    ExteriorColor.BLUE, "Lyon", "69006", 23),
            new Vehicle("garage-bellevue", "mercedes-benz", "classe-a", "A 200 163 AMG Line",
                    "Mercedes Classe A 200 AMG Line", 25_490, 2021, 10, 49_300,
                    FuelType.PETROL, Gearbox.AUTOMATIC, BodyType.HATCHBACK, 163, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Lyon", "69002", 31),
            new Vehicle("garage-bellevue", "tesla", "model-y", "Propulsion",
                    "Tesla Model Y Propulsion — neuve", 44_990, 2026, 8, 25,
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
                    "Renault Mégane Estate dCi 115 Business", 9_900, 2019, 2, 142_000,
                    FuelType.DIESEL, Gearbox.MANUAL, BodyType.ESTATE, 115, VehicleCondition.USED,
                    ExteriorColor.GREY, "Strasbourg", "67000", 8),
            new Vehicle("thomas", "dacia", "sandero", "SCe 75 Essentiel",
                    "Dacia Sandero SCe 75, faible kilométrage", 7_200, 2018, 12, 58_400,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.HATCHBACK, 75, VehicleCondition.USED,
                    ExteriorColor.WHITE, "Strasbourg", "67200", 52),
            // The first 5008, 2009 to 2016, was a seven-seat monospace; the one sold since
            // 2017 is an SUV. Written by hand rather than generated: at ten years old the
            // generator's depreciation prices it below what such a car really fetches.
            new Vehicle("thomas", "peugeot", "5008", "1.6 BlueHDi 120 Allure",
                    "Peugeot 5008 BlueHDi 120 Allure 7 places", 8_900, 2015, 9, 148_000,
                    FuelType.DIESEL, Gearbox.MANUAL, BodyType.MPV, 120, VehicleCondition.USED,
                    ExteriorColor.GREY, "Strasbourg", "67000", 27),
            new Vehicle("garage-bellevue", "peugeot", "5008", "1.2 PureTech 130 Allure",
                    "Peugeot 5008 PureTech 130 Allure 7 places, entretien à jour", 10_490, 2016, 4, 96_500,
                    FuelType.PETROL, Gearbox.MANUAL, BodyType.MPV, 130, VehicleCondition.USED,
                    ExteriorColor.BLACK, "Lyon", "69008", 44));
}
