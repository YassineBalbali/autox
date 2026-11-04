package com.autox.savedsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.LeasingType;
import com.autox.listing.OfferType;
import com.autox.listing.VehicleCondition;
import com.autox.search.DamageFilter;
import com.autox.search.ListingSearchCriteria;
import com.autox.search.PriceLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What is stored for a search has to come back as the same search, now and after a change. */
class SavedSearchCriteriaCodecTest {

    private final SavedSearchCriteriaCodec codec = new SavedSearchCriteriaCodec(new ObjectMapper());

    private static final ListingSearchCriteria EVERY_FILTER = new ListingSearchCriteria(
            UUID.randomUUID(), UUID.randomUUID(), "Audi", "A3",
            2015, 2022, 100_000L, 2_000_000L, "EUR", 1_000, 90_000,
            List.of(FuelType.DIESEL), List.of(Gearbox.MANUAL), List.of(BodyType.values()[0]),
            List.of(Drivetrain.values()[0]), List.of(VehicleCondition.USED),
            "FR", "Lyon",
            new BigDecimal("45.1"), new BigDecimal("46.2"), new BigDecimal("4.3"), new BigDecimal("5.4"),
            "golf gti", List.of(PriceLevel.GOOD_DEAL), DamageFilter.ALL, OfferType.LEASING,
            List.of(LeasingType.LOA), 10_000L, 50_000L, 24, 48, 10_000, 30_000);

    @Test
    @DisplayName("every filter survives being stored")
    void everyFilter() {
        for (RecordComponent component : ListingSearchCriteria.class.getRecordComponents()) {
            assertThat(accessor(component, EVERY_FILTER)).as(component.getName() + " is set in this test").isNotNull();
        }

        String stored = codec.write(EVERY_FILTER);

        assertThat(codec.readTree(SavedSearchCriteriaCodec.CURRENT_VERSION, stored).flatMap(codec::bind))
                .contains(EVERY_FILTER);
    }

    @Test
    @DisplayName("what is not set is not stored")
    void compact() {
        ListingSearchCriteria nothing = new ListingSearchCriteria(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThat(codec.write(nothing)).isEqualTo("{}");
    }

    @Test
    @DisplayName("a field that has gone is ignored, and a value that has gone is dropped from its list")
    void laterChanges() {
        ListingSearchCriteria read = codec.readTree(1, """
                {"fuelType":["DIESEL","VAPEUR"],"champSupprime":{"a":1},"yearFrom":2018,"damage":"DISPARU"}
                """).flatMap(codec::bind).orElseThrow();

        assertThat(read.fuelType()).containsExactly(FuelType.DIESEL);
        assertThat(read.yearFrom()).isEqualTo(2018);
        assertThat(read.damage()).isNull();
    }

    @Test
    @DisplayName("what cannot be understood is empty, not an exception")
    void unreadable() {
        assertThat(codec.readTree(SavedSearchCriteriaCodec.CURRENT_VERSION + 1, "{}")).isEmpty();
        assertThat(codec.readTree(0, "{}")).isEmpty();
        assertThat(codec.readTree(1, "pas du json")).isEmpty();
        assertThat(codec.readTree(1, "[1,2]")).isEmpty();
        assertThat(codec.readTree(1, "{\"yearFrom\":\"bientôt\"}").flatMap(codec::bind)).isEmpty();
    }

    private static Object accessor(RecordComponent component, Object record) {
        try {
            assertThat(Modifier.isPublic(component.getAccessor().getModifiers())).isTrue();
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
