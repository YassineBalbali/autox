package com.autox.listing.dto;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.EmissionClass;
import com.autox.listing.ExteriorColor;
import com.autox.listing.InteriorMaterial;
import com.autox.listing.VehicleCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * A new listing. Created as a DRAFT; nothing here publishes it.
 *
 * <p>Two either/or rules are enforced before the request reaches the service, mirroring
 * the database's own constraints:
 * <ul>
 *   <li><b>Vehicle</b>: each of make and model is given either as a catalogue id or as
 *       text, never both and never neither. A trim requires a model.</li>
 *   <li><b>Location</b>: either a gazetteer place id, or postal code plus city as text
 *       while the gazetteer is unavailable.</li>
 * </ul>
 * Numeric bounds repeat the table's CHECK constraints so a bad value is a 400 with a
 * field name rather than a 500 from the driver.
 */
public record CreateListingRequest(

        @NotBlank @Size(max = 140) String title,
        @Size(max = 10000) String description,

        /** Minor units of {@code currencyCode} — cents, not euros. */
        @NotNull @Min(0) @Max(1_000_000_000) Long priceAmount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code")
        String currencyCode,
        Boolean priceNegotiable,
        Boolean vatDeductible,

        // --- location: gazetteer reference, or free text ---
        UUID geoPlaceId,
        @Size(max = 12) String postalCode,
        @Size(max = 120) String city,
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter ISO country code")
        String countryCode,
        Boolean hideExactLocation,

        // --- vehicle: catalogue reference, or free text ---
        UUID makeId,
        UUID modelId,
        UUID trimId,
        @Size(max = 80) String makeText,
        @Size(max = 120) String modelText,
        @Size(max = 160) String versionText,

        @NotNull @Min(1900) @Max(2100) Integer firstRegistrationYear,
        @Min(1) @Max(12) Integer firstRegistrationMonth,
        @NotNull @Min(0) @Max(3_000_000) Integer mileageKm,

        @NotNull FuelType fuelType,
        @NotNull Gearbox gearbox,
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

        @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$",
                message = "must be 17 characters, excluding I, O and Q")
        String vin,

        Set<@NotBlank @Size(max = 60) String> featureCodes) {

    @Schema(hidden = true)
    @AssertTrue(message = "supply exactly one of makeId or makeText")
    public boolean isMakeIdentifiedOnce() {
        return (makeId != null) != (makeText != null && !makeText.isBlank());
    }

    @Schema(hidden = true)
    @AssertTrue(message = "supply exactly one of modelId or modelText")
    public boolean isModelIdentifiedOnce() {
        return (modelId != null) != (modelText != null && !modelText.isBlank());
    }

    @Schema(hidden = true)
    @AssertTrue(message = "modelId is required when makeId is given, and vice versa")
    public boolean isCatalogueReferenceComplete() {
        return (makeId == null) == (modelId == null);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "trimId requires modelId")
    public boolean isTrimConsistent() {
        return trimId == null || modelId != null;
    }

    @Schema(hidden = true)
    @AssertTrue(message = "supply either geoPlaceId, or postalCode with city and countryCode")
    public boolean isLocationIdentified() {
        boolean byReference = geoPlaceId != null;
        boolean byText = postalCode != null && !postalCode.isBlank()
                && city != null && !city.isBlank()
                && countryCode != null && !countryCode.isBlank();
        return byReference != byText;
    }
}
