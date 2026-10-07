package com.autox.support;

import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleMakeRepository;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleModelRepository;
import com.autox.catalog.VehicleTrim;
import com.autox.catalog.VehicleTrimRepository;
import com.autox.reference.CountryRepository;
import com.autox.reference.GeoPlace;
import com.autox.reference.GeoPlaceRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalogue and gazetteer rows for tests.
 *
 * <p>Both datasets are deferred in production, so there is no seed migration to lean on
 * and the catalogue-reference path would otherwise be untestable. These are the minimum
 * rows needed to exercise it, created through the same factories the eventual import job
 * will use — not a substitute for the real datasets.
 */
@Component
public class ListingTestFixtures {

    private final VehicleMakeRepository makes;
    private final VehicleModelRepository models;
    private final VehicleTrimRepository trims;
    private final GeoPlaceRepository geoPlaces;
    private final CountryRepository countries;

    public ListingTestFixtures(VehicleMakeRepository makes, VehicleModelRepository models,
                               VehicleTrimRepository trims, GeoPlaceRepository geoPlaces,
                               CountryRepository countries) {
        this.makes = makes;
        this.models = models;
        this.trims = trims;
        this.geoPlaces = geoPlaces;
        this.countries = countries;
    }

    /** Idempotent: the cleaner leaves reference tables alone, so this runs once in practice. */
    @Transactional
    public Catalogue ensureCatalogue() {
        VehicleMake bmw = makeBySlug("bmw").orElseGet(() -> makes.saveAndFlush(VehicleMake.of("bmw", "BMW")));
        VehicleMake peugeot = makeBySlug("peugeot")
                .orElseGet(() -> makes.saveAndFlush(VehicleMake.of("peugeot", "Peugeot")));

        VehicleModel threeSeries = modelBySlug("3-series")
                .orElseGet(() -> models.saveAndFlush(VehicleModel.of(bmw, "3-series", "3 Series")));
        VehicleModel twoOhEight = modelBySlug("208")
                .orElseGet(() -> models.saveAndFlush(VehicleModel.of(peugeot, "208", "208")));

        VehicleTrim threeTwentyD = trims.findAll().stream()
                .filter(trim -> trim.getSlug().equals("320d-xdrive"))
                .findFirst()
                .orElseGet(() -> trims.saveAndFlush(VehicleTrim.of(threeSeries, "320d-xdrive", "320d xDrive")));

        return new Catalogue(bmw, threeSeries, threeTwentyD, peugeot, twoOhEight);
    }

    @Transactional
    public GeoPlace ensureParis() {
        return geoPlaces.findAll().stream()
                .filter(place -> place.getPostalCode().equals("75011"))
                .findFirst()
                .orElseGet(() -> geoPlaces.saveAndFlush(GeoPlace.of(
                        countries.findEnabledByCode("FR").orElseThrow(),
                        "75011", "Paris",
                        new BigDecimal("48.858370"), new BigDecimal("2.294481"))));
    }

    private java.util.Optional<VehicleMake> makeBySlug(String slug) {
        return makes.findAll().stream().filter(make -> make.getSlug().equals(slug)).findFirst();
    }

    private java.util.Optional<VehicleModel> modelBySlug(String slug) {
        return models.findAll().stream().filter(model -> model.getSlug().equals(slug)).findFirst();
    }

    public record Catalogue(VehicleMake bmw, VehicleModel threeSeries, VehicleTrim threeTwentyD,
                            VehicleMake peugeot, VehicleModel twoOhEight) {
    }

    /** A valid create payload using the free-text location and vehicle paths. */
    public static Map<String, Object> freeTextListingPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "BMW 320d xDrive M Sport");
        payload.put("description", "Well maintained, full service history.");
        payload.put("priceAmount", 1899000L);
        payload.put("currencyCode", "EUR");
        payload.put("priceNegotiable", true);
        payload.put("postalCode", "75011");
        payload.put("city", "Paris");
        payload.put("countryCode", "FR");
        payload.put("makeText", "BMW");
        payload.put("modelText", "3 Series");
        payload.put("versionText", "320d xDrive M Sport");
        payload.put("firstRegistrationYear", 2019);
        payload.put("firstRegistrationMonth", 6);
        payload.put("mileageKm", 87000);
        payload.put("fuelType", "DIESEL");
        payload.put("gearbox", "AUTOMATIC");
        payload.put("bodyType", "SEDAN");
        payload.put("powerHp", 190);
        payload.put("doors", 4);
        payload.put("seats", 5);
        payload.put("condition", "USED");
        payload.put("exteriorColor", "BLACK");
        return payload;
    }
}
