package com.autox.search;

import com.autox.catalog.FuelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * The car a visitor wants valued, as query parameters.
 *
 * <p>Only what chooses comparables or adjusts their prices: the catalogue model, the engine
 * (fuel and power), the first registration and the mileage. The gearbox and the version name
 * are not here on purpose: the price indicator does not compare on them, and La Cote values
 * a car the way the indicator judges one, so the two never disagree about the same car.
 *
 * @param scope    the price indicator's rule, or one loosening of it; EXACT when absent
 * @param currency the currency to value in, which is the currency of the comparables; EUR
 *                 when absent
 */
public record ValuationRequest(
        @NotNull UUID modelId,
        @NotNull FuelType fuelType,
        @NotNull @Min(1) @Max(2000) Integer powerHp,
        @NotNull @Min(1900) @Max(2100) Integer year,
        @Min(1) @Max(12) Integer month,
        @NotNull @Min(0) @Max(3_000_000) Integer mileageKm,
        ValuationScope scope,
        @Pattern(regexp = "^[A-Z]{3}$") String currency) {

    ValuationScope scopeOrExact() {
        return scope == null ? ValuationScope.EXACT : scope;
    }

    String currencyOrEuro() {
        return currency == null ? "EUR" : currency;
    }
}
