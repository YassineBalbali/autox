package com.autox.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the assistant may know about a listing, held to a list. Adding a field means changing this
 * test, and the review that goes with it: is it something a seller typed, or something about them?
 */
class ListingFactsTest {

    @Test
    @DisplayName("has exactly the allowed fields: no title, description, version, seller identity or VIN")
    void allowList() {
        assertThat(Arrays.stream(ListingFacts.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "make", "model", "year", "month", "mileageKm", "fuelType", "gearbox",
                        "bodyType", "powerHp", "priceAmount", "currencyCode", "offer", "leasing", "condition",
                        "soldNew", "damageTypes", "damageRunning", "verdict", "critAir", "city", "sellerRole");
    }

    @Test
    @DisplayName("takes from the leasing terms only figures and codes")
    void leasingHasNoText() {
        assertThat(Arrays.stream(com.autox.listing.dto.LeasingView.class.getRecordComponents())
                .map(RecordComponent::getType))
                .allSatisfy(type -> assertThat(type).isNotEqualTo(String.class));
    }
}
