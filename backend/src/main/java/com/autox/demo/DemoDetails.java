package com.autox.demo;

import com.autox.catalog.BodyType;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.EmissionClass;
import com.autox.listing.VehicleCondition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Everything a listing page shows that a results row does not: doors, seats, emissions,
 * equipment and a description.
 *
 * <p>Derived from the vehicle rather than written out sixty times, and derived
 * deterministically — the same car always gets the same equipment. Plausible is the bar:
 * an electric car emits nothing, a newer and dearer car has more options, a 2018 diesel
 * is Euro 6. None of it describes a real vehicle.
 */
final class DemoDetails {

    /** What almost any car of the last decade has. */
    private static final List<String> STANDARD = List.of(
            "ABS", "ESP", "ISOFIX", "AIR_CONDITIONING", "ELECTRIC_WINDOWS", "BLUETOOTH");

    /** Options, cheapest first: a car gets the first few, and more the newer and dearer it is. */
    private static final List<String> OPTIONS = List.of(
            "CRUISE_CONTROL", "PARKING_SENSORS_REAR", "ALLOY_WHEELS", "TYRE_PRESSURE_MONITOR",
            "APPLE_CARPLAY", "ANDROID_AUTO", "LEATHER_STEERING", "CLIMATE_CONTROL_AUTO",
            "LED_HEADLIGHTS", "REVERSING_CAMERA", "NAVIGATION", "KEYLESS_ENTRY", "DAB_RADIO",
            "EMERGENCY_BRAKING", "LANE_ASSIST", "PARKING_SENSORS_FRONT", "HEATED_SEATS",
            "ADAPTIVE_CRUISE", "BLIND_SPOT_MONITOR", "AMBIENT_LIGHTING", "PANORAMIC_ROOF",
            "ELECTRIC_SEATS");

    private DemoDetails() {
    }

    /**
     * A van has four: two at the front, a sliding one at the side, and the rear doors, counted
     * as one as French listings count them.
     */
    static int doors(DemoCatalogue.Vehicle vehicle) {
        if (vehicle.modelSlug().equals("500")) {
            return 3;
        }
        return vehicle.body() == BodyType.SEDAN || vehicle.body() == BodyType.VAN ? 4 : 5;
    }

    /** A van's cab: a bench for two passengers in the larger Jumpy, a single seat in the others. */
    static int seats(DemoCatalogue.Vehicle vehicle) {
        if (vehicle.body() == BodyType.VAN) {
            return vehicle.modelSlug().equals("jumpy") ? 3 : 2;
        }
        return switch (vehicle.modelSlug()) {
            case "500" -> 4;
            case "5008" -> 7;
            default -> 5;
        };
    }

    static int powerKw(DemoCatalogue.Vehicle vehicle) {
        return (int) Math.round(vehicle.powerHp() * 0.7355);
    }

    /** Grams of CO₂ per kilometre: nothing for a battery, little for a plug-in. */
    static Integer co2(DemoCatalogue.Vehicle vehicle) {
        return switch (vehicle.fuel()) {
            case ELECTRIC -> 0;
            case PLUGIN_HYBRID -> 28 + vehicle.powerHp() / 20;
            case HYBRID_PETROL, HYBRID_DIESEL -> 88 + vehicle.powerHp() / 10;
            case DIESEL -> 96 + vehicle.powerHp() / 6;
            default -> 104 + vehicle.powerHp() / 5;
        };
    }

    static EmissionClass emissionClass(DemoCatalogue.Vehicle vehicle) {
        if (vehicle.year() >= 2021) {
            return EmissionClass.EURO_6D;
        }
        return vehicle.year() >= 2015 ? EmissionClass.EURO_6 : EmissionClass.EURO_5;
    }

    /** {@code null} for a new car, which has had no owner. */
    static Integer owners(DemoCatalogue.Vehicle vehicle) {
        if (vehicle.condition() == VehicleCondition.NEW) {
            return null;
        }
        int age = Math.max(0, 2026 - vehicle.year());
        return Math.min(3, 1 + age / 4);
    }

    /** The standard set, then as many options as the car's age and price justify. */
    static Set<String> features(DemoCatalogue.Vehicle vehicle) {
        Set<String> codes = new LinkedHashSet<>(STANDARD);

        int age = Math.max(0, 2026 - vehicle.year());
        int count = (int) Math.min(OPTIONS.size(), 4 + vehicle.priceEuros() / 3_500 - age / 2);
        codes.addAll(OPTIONS.subList(0, Math.max(3, count)));

        // A little variation between two otherwise identical cars, from the title alone
        // so it is the same on every run.
        Random random = new Random(vehicle.title().hashCode());
        if (random.nextBoolean()) {
            codes.add("ROOF_RAILS");
        }
        if (vehicle.fuel() == FuelType.DIESEL) {
            codes.add("PARTICLE_FILTER");
        }
        if (vehicle.body() == BodyType.VAN) {
            // No rear seats, so nothing to fix a child seat to.
            codes.remove("ISOFIX");
        }
        if (vehicle.fuel() != FuelType.ELECTRIC && vehicle.gearbox() == Gearbox.AUTOMATIC) {
            codes.add("START_STOP");
        }
        return codes;
    }

    /** The same for a damaged vehicle, whose state its seller has declared and this must not contradict. */
    static String damagedDescription(DemoCatalogue.DamagedVehicle damaged) {
        DemoCatalogue.Vehicle vehicle = damaged.vehicle();
        return String.join("\n\n",
                vehicle.title() + ".",
                "Vendu en l’état, sans garantie. À voir à " + vehicle.city() + " sur rendez-vous.",
                NOTICE);
    }

    /**
     * The last paragraph of every demo description. In words that fit the public demo, where
     * visitors read it: an earlier one said the listing illustrated "AutoX en développement
     * local", which was not true of the site it was on.
     */
    static final String NOTICE =
            "Annonce de démonstration : ce véhicule n’existe pas. Elle montre le fonctionnement d’AutoX.";

    /** The same for a leasing offer, which invents an offer as well as a car. */
    static final String LEASING_NOTICE = "Annonce de démonstration : ce véhicule et cette offre "
            + "n’existent pas. Elle montre le fonctionnement d’AutoX.";

    /** The same for a leasing offer. */
    static String leasedDescription(DemoCatalogue.LeasedVehicle leased) {
        DemoCatalogue.Vehicle vehicle = leased.vehicle();
        return String.join("\n\n",
                vehicle.title() + ".",
                "Disponible auprès de notre concession de " + vehicle.city() + ". Conditions détaillées sur demande.",
                LEASING_NOTICE);
    }

    /** The same for a new car: its own sentence, then what every new car from a dealer has. */
    static String newDescription(DemoCatalogue.NewVehicle car) {
        DemoCatalogue.Vehicle vehicle = car.vehicle();
        return String.join("\n\n",
                vehicle.title() + ".",
                car.pitch(),
                "Véhicule neuf, garantie constructeur. Visible à " + vehicle.city() + " sur rendez-vous.",
                NOTICE);
    }

    /** A few sentences in the register of a real listing, and one that says it is not one. */
    static String description(DemoCatalogue.Vehicle vehicle) {
        String state = vehicle.condition() == VehicleCondition.NEW
                ? "Véhicule neuf, disponible immédiatement."
                : "Véhicule en très bon état général, intérieur soigné, non fumeur.";
        String history = vehicle.condition() == VehicleCondition.NEW
                ? "Garantie constructeur."
                : "Entretien suivi, factures à l’appui. Contrôle technique à jour.";

        return String.join("\n\n",
                vehicle.title() + ".",
                state + " " + history,
                "Visible à " + vehicle.city() + " sur rendez-vous. Essai possible.",
                NOTICE);
    }
}
