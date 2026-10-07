package com.autox.listing.dto;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.catalog.VehicleFeature;
import com.autox.listing.EmissionClass;
import com.autox.listing.ExteriorColor;
import com.autox.listing.InteriorMaterial;
import com.autox.listing.Listing;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationStatus;
import com.autox.listing.VehicleCondition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's view of a listing. Includes the states and timestamps a seller needs to
 * understand what will happen next, and {@code allowedTransitions} so a client can
 * render exactly the buttons that will work rather than guessing the state machine.
 *
 * <p>The public buyer-facing shape will be narrower — no VIN, no moderation detail — and
 * arrives with search.
 */
public record ListingResponse(
        UUID id,
        UUID sellerId,
        ListingStatus status,
        ModerationStatus moderationStatus,
        Set<ListingStatus> allowedTransitions,

        String title,
        String description,

        long priceAmount,
        String currencyCode,
        boolean priceNegotiable,
        Boolean vatDeductible,

        LocationView location,
        VehicleView vehicle,

        int mileageKm,
        VehicleCondition condition,
        ExteriorColor exteriorColor,
        String colorText,
        Boolean metallicPaint,
        InteriorMaterial interiorMaterial,
        Integer ownerCount,
        Boolean serviceHistory,
        Integer warrantyMonths,
        LocalDate technicalInspectionValidUntil,
        Integer co2EmissionsGKm,
        EmissionClass emissionClass,
        String vin,

        List<String> featureCodes,

        /** Ready photos in gallery order; the first is the cover. */
        List<ImageResponse> images,

        long viewCount,
        Instant publishedAt,
        Instant expiresAt,
        Instant soldAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ListingResponse from(Listing listing, List<ImageResponse> images) {
        return new ListingResponse(
                listing.getId(),
                listing.getSeller().getId(),
                listing.getStatus(),
                listing.getModerationStatus(),
                listing.getStatus().allowedTargets(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPriceAmount(),
                listing.getPriceCurrency().getCode(),
                listing.isPriceNegotiable(),
                listing.getVatDeductible(),
                LocationView.from(listing),
                VehicleView.from(listing),
                listing.getMileageKm(),
                listing.getVehicleCondition(),
                listing.getExteriorColor(),
                listing.getColorText(),
                listing.getMetallicPaint(),
                listing.getInteriorMaterial(),
                toInteger(listing.getOwnerCount()),
                listing.getServiceHistory(),
                toInteger(listing.getWarrantyMonths()),
                listing.getTechnicalInspectionValidUntil(),
                toInteger(listing.getCo2EmissionsGKm()),
                listing.getEmissionClass(),
                listing.getVin(),
                listing.getFeatures().stream().map(VehicleFeature::getCode).sorted().toList(),
                images == null ? List.of() : images,
                listing.getViewCount(),
                listing.getPublishedAt(),
                listing.getExpiresAt(),
                listing.getSoldAt(),
                listing.getCreatedAt(),
                listing.getUpdatedAt());
    }

    /** {@code resolved} is false while the location is still unmatched free text. */
    public record LocationView(
            UUID geoPlaceId,
            String countryCode,
            String postalCode,
            String city,
            boolean hideExactLocation,
            boolean resolved) {

        static LocationView from(Listing listing) {
            boolean resolved = listing.getGeoPlace() != null;
            return new LocationView(
                    resolved ? listing.getGeoPlace().getId() : null,
                    listing.getCountry().getCode(),
                    resolved ? listing.getGeoPlace().getPostalCode() : listing.getPostalCode(),
                    resolved ? listing.getGeoPlace().getCity() : listing.getCity(),
                    listing.isHideExactLocation(),
                    resolved);
        }
    }

    /** {@code fromCatalogue} is false when the vehicle is identified by free text. */
    public record VehicleView(
            UUID makeId,
            String make,
            UUID modelId,
            String model,
            UUID trimId,
            String trim,
            String version,
            boolean fromCatalogue,
            int firstRegistrationYear,
            Integer firstRegistrationMonth,
            FuelType fuelType,
            Gearbox gearbox,
            BodyType bodyType,
            Drivetrain drivetrain,
            Integer powerHp,
            Integer powerKw,
            Integer engineDisplacementCc,
            Integer doors,
            Integer seats) {

        static VehicleView from(Listing listing) {
            boolean fromCatalogue = listing.getMake() != null;
            return new VehicleView(
                    fromCatalogue ? listing.getMake().getId() : null,
                    fromCatalogue ? listing.getMake().getName() : listing.getMakeText(),
                    listing.getModel() == null ? null : listing.getModel().getId(),
                    listing.getModel() == null ? listing.getModelText() : listing.getModel().getName(),
                    listing.getTrim() == null ? null : listing.getTrim().getId(),
                    listing.getTrim() == null ? null : listing.getTrim().getName(),
                    listing.getVersionText(),
                    fromCatalogue,
                    listing.getFirstRegistrationYear(),
                    toInteger(listing.getFirstRegistrationMonth()),
                    listing.getFuelType(),
                    listing.getGearbox(),
                    listing.getBodyType(),
                    listing.getDrivetrain(),
                    toInteger(listing.getPowerHp()),
                    toInteger(listing.getPowerKw()),
                    listing.getEngineDisplacementCc(),
                    toInteger(listing.getDoors()),
                    toInteger(listing.getSeats()));
        }
    }

    private static Integer toInteger(Short value) {
        return value == null ? null : value.intValue();
    }
}
