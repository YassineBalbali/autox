package com.autox.search.dto;

import com.autox.catalog.VehicleFeature;
import com.autox.listing.Listing;
import com.autox.listing.dto.ImageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The buyer-facing detail page.
 *
 * <p>Narrower than the seller's own view of the same listing. Absent on purpose: the VIN
 * (identifies the vehicle and indirectly its owner), the moderation state (an internal
 * matter), the exact coordinates, and anything about the seller beyond a display name.
 *
 * <p>As in the summary, every enumerated value is a stable code and every feature is a code.
 * There is no human-readable label anywhere in this response.
 */
public record PublicListingDetail(
        UUID id,
        String title,
        String description,

        long priceAmount,
        String currencyCode,
        boolean priceNegotiable,
        Boolean vatDeductible,

        VehicleView vehicle,
        LocationView location,
        SellerView seller,

        int mileageKm,
        String condition,
        String exteriorColor,
        String colorText,
        Boolean metallicPaint,
        String interiorMaterial,
        Integer ownerCount,
        Boolean serviceHistory,
        Integer warrantyMonths,
        LocalDate technicalInspectionValidUntil,
        Integer co2EmissionsGKm,
        String emissionClass,

        /** Equipment codes; the frontend holds the translations. */
        List<String> featureCodes,
        List<ImageResponse> images,

        /** Against comparable listings; null when there is not enough to compare with. */
        PriceIndicator priceIndicator,

        Instant publishedAt,
        Instant updatedAt) {

    public static PublicListingDetail from(Listing listing, List<ImageResponse> images,
                                           PriceIndicator priceIndicator) {
        return new PublicListingDetail(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPriceAmount(),
                listing.getPriceCurrency().getCode(),
                listing.isPriceNegotiable(),
                listing.getVatDeductible(),
                VehicleView.from(listing),
                LocationView.from(listing),
                SellerView.from(listing),
                listing.getMileageKm(),
                name(listing.getVehicleCondition()),
                name(listing.getExteriorColor()),
                listing.getColorText(),
                listing.getMetallicPaint(),
                name(listing.getInteriorMaterial()),
                toInteger(listing.getOwnerCount()),
                listing.getServiceHistory(),
                toInteger(listing.getWarrantyMonths()),
                listing.getTechnicalInspectionValidUntil(),
                toInteger(listing.getCo2EmissionsGKm()),
                name(listing.getEmissionClass()),
                listing.getFeatures().stream().map(VehicleFeature::getCode).sorted().toList(),
                images,
                priceIndicator,
                listing.getPublishedAt(),
                listing.getUpdatedAt());
    }

    public record VehicleView(
            UUID makeId,
            String make,
            UUID modelId,
            String model,
            String version,
            int firstRegistrationYear,
            Integer firstRegistrationMonth,
            String fuelType,
            String gearbox,
            String bodyType,
            String drivetrain,
            Integer powerHp,
            Integer powerKw,
            Integer engineDisplacementCc,
            Integer doors,
            Integer seats) {

        static VehicleView from(Listing listing) {
            boolean catalogued = listing.getMake() != null;
            return new VehicleView(
                    catalogued ? listing.getMake().getId() : null,
                    catalogued ? listing.getMake().getName() : listing.getMakeText(),
                    listing.getModel() == null ? null : listing.getModel().getId(),
                    listing.getModel() == null ? listing.getModelText() : listing.getModel().getName(),
                    listing.getTrim() == null ? listing.getVersionText() : listing.getTrim().getName(),
                    listing.getFirstRegistrationYear(),
                    toInteger(listing.getFirstRegistrationMonth()),
                    name(listing.getFuelType()),
                    name(listing.getGearbox()),
                    name(listing.getBodyType()),
                    name(listing.getDrivetrain()),
                    toInteger(listing.getPowerHp()),
                    toInteger(listing.getPowerKw()),
                    listing.getEngineDisplacementCc(),
                    toInteger(listing.getDoors()),
                    toInteger(listing.getSeats()));
        }
    }

    /**
     * City and postal code only. Coordinates are never published: combined with a photo of a
     * house they identify an address, and {@code hideExactLocation} defaults to true.
     */
    public record LocationView(String countryCode, String city, String postalCode) {

        static LocationView from(Listing listing) {
            boolean resolved = listing.getGeoPlace() != null;
            return new LocationView(
                    listing.getCountry().getCode(),
                    resolved ? listing.getGeoPlace().getCity() : listing.getCity(),
                    resolved ? listing.getGeoPlace().getPostalCode() : listing.getPostalCode());
        }
    }

    /** {@code role} tells a buyer whether this is a dealer or a private sale. Nothing more. */
    public record SellerView(UUID id, String displayName, String role, Instant memberSince) {

        static SellerView from(Listing listing) {
            var seller = listing.getSeller();
            return new SellerView(seller.getId(), seller.getDisplayName(),
                    seller.getRole().name(), seller.getCreatedAt());
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Integer toInteger(Short value) {
        return value == null ? null : value.intValue();
    }
}
