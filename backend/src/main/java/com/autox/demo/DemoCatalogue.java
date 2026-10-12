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
 * <p>Invented for local development, so the search screen has something to show. Chosen
 * to exercise every filter rather than to look like a real dealer's stock: each fuel,
 * both gearboxes, six body types, new and used, prices from 7 000 to 49 000 €, and
 * posting dates spread over two months so the four sort orders give four different lists.
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
            new Seller("thomas", UserRole.PARTICULIER, "Thomas L.", null, null));

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
