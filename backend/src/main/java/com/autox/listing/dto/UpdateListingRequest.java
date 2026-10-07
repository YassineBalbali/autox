package com.autox.listing.dto;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.EmissionClass;
import com.autox.listing.ExteriorColor;
import com.autox.listing.InteriorMaterial;
import com.autox.listing.VehicleCondition;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Partial update. {@code null} means "leave unchanged".
 *
 * <p>Status is not a field here: moving between states goes through the publish,
 * unpublish, renew and delete endpoints, which is what makes the transition rules
 * impossible to bypass. Nor is the seller — a listing never changes hands.
 *
 * <p>Vehicle and location identification may be switched between catalogue reference and
 * free text, but only coherently: supplying a {@code makeId} clears {@code makeText} and
 * the reverse, and supplying both is rejected.
 */
public record UpdateListingRequest(

        @Size(min = 1, max = 140) String title,
        @Size(max = 10000) String description,

        @Min(0) @Max(1_000_000_000) Long priceAmount,
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code")
        String currencyCode,
        Boolean priceNegotiable,
        Boolean vatDeductible,

        UUID geoPlaceId,
        @Size(max = 12) String postalCode,
        @Size(max = 120) String city,
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter ISO country code")
        String countryCode,
        Boolean hideExactLocation,

        UUID makeId,
        UUID modelId,
        UUID trimId,
        @Size(max = 80) String makeText,
        @Size(max = 120) String modelText,
        @Size(max = 160) String versionText,

        @Min(1900) @Max(2100) Integer firstRegistrationYear,
        @Min(1) @Max(12) Integer firstRegistrationMonth,
        @Min(0) @Max(3_000_000) Integer mileageKm,

        FuelType fuelType,
        Gearbox gearbox,
        BodyType bodyType,
        Drivetrain drivetrain,

        @Min(1) @Max(2000) Integer powerHp,
        @Min(1) @Max(1500) Integer powerKw,
        @Min(50) @Max(10000) Integer engineDisplacementCc,
        @Min(1) @Max(7) Integer doors,
        @Min(1) @Max(9) Integer seats,

        VehicleCondition condition,
        ExteriorColor exteriorColor,
        @Size(max = 60) String colorText,
        Boolean metallicPaint,
        InteriorMaterial interiorMaterial,

        @Min(0) @Max(50) Integer ownerCount,
        Boolean serviceHistory,
        @Min(0) @Max(120) Integer warrantyMonths,
        LocalDate technicalInspectionValidUntil,
        @Min(0) @Max(1000) Integer co2EmissionsGKm,
        EmissionClass emissionClass,

        @Pattern(regexp = "^([A-HJ-NPR-Z0-9]{17})?$",
                message = "must be 17 characters excluding I, O and Q, or empty to clear")
        String vin,

        /** Replaces the whole set when present. An empty set clears every feature. */
        Set<@NotBlank @Size(max = 60) String> featureCodes) {

    @AssertTrue(message = "supply makeId or makeText, not both")
    public boolean isMakeUnambiguous() {
        return makeId == null || makeText == null || makeText.isBlank();
    }

    @AssertTrue(message = "supply modelId or modelText, not both")
    public boolean isModelUnambiguous() {
        return modelId == null || modelText == null || modelText.isBlank();
    }

    @AssertTrue(message = "trimId requires a model to be identified by catalogue id")
    public boolean isTrimConsistent() {
        return trimId == null || modelId != null;
    }

    @AssertTrue(message = "supply geoPlaceId, or postalCode with city, not both")
    public boolean isLocationUnambiguous() {
        boolean byText = (postalCode != null && !postalCode.isBlank()) || (city != null && !city.isBlank());
        return geoPlaceId == null || !byText;
    }
}
