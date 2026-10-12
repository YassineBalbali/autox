package com.autox.search;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.VehicleCondition;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything a buyer can filter on. Every field is optional; an empty criteria object means
 * "all visible listings".
 *
 * <p>Make and model can each be given two ways, and both are needed:
 * <ul>
 *   <li>{@code makeId} matches the catalogue exactly — precise, and what the frontend's
 *       dropdown will send once the catalogue exists.</li>
 *   <li>{@code make} matches the displayed name, catalogue row <em>or</em> free text. Until the
 *       catalogue is seeded, every listing identifies its vehicle by text, so an id-only
 *       filter would match nothing at all.</li>
 * </ul>
 */
public record ListingSearchCriteria(

        UUID makeId,
        UUID modelId,
        @Size(max = 80) String make,
        @Size(max = 120) String model,

        @Min(1900) @Max(2100) Integer yearFrom,
        @Min(1900) @Max(2100) Integer yearTo,

        /** Minor units, matching how prices are stored. */
        @Min(0) Long priceMin,
        @Min(0) Long priceMax,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,

        @Min(0) Integer mileageMin,
        @Min(0) @Max(3_000_000) Integer mileageMax,

        List<FuelType> fuelType,
        List<Gearbox> gearbox,
        List<BodyType> bodyType,
        List<Drivetrain> drivetrain,
        List<VehicleCondition> condition,

        @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,
        /** Matches city or postal code, accent- and case-insensitively. */
        @Size(max = 120) String location,

        /** Bounding box, ready for when the gazetteer lands and listings carry coordinates. */
        BigDecimal latMin,
        BigDecimal latMax,
        BigDecimal lonMin,
        BigDecimal lonMax,

        /**
         * Free text, as typed into a search box: "golf tdi", "clio automatique".
         *
         * <p>Split on whitespace; a listing matches when <em>every</em> word appears in its
         * title, make, model or version, case-insensitively. All words rather than any,
         * because a second word is a buyer narrowing the search — "golf tdi" returning
         * every Golf and every TDI would make typing more return more.
         *
         * <p>Last in the record on purpose: it was added after the others, and appending
         * keeps every existing positional construction compiling.
         */
        @Size(max = 120) String q) {

    public boolean hasBoundingBox() {
        return latMin != null && latMax != null && lonMin != null && lonMax != null;
    }

    /** A half-specified box is a client bug; better to say so than to ignore it silently. */
    public boolean hasPartialBoundingBox() {
        boolean any = latMin != null || latMax != null || lonMin != null || lonMax != null;
        return any && !hasBoundingBox();
    }
}
