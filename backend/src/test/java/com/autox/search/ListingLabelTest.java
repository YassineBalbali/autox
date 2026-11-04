package com.autox.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ListingLabelTest {

    @Test
    @DisplayName("make, model and version, as on a result card, and never the title beside them")
    void makeModelVersion() {
        assertThat(ListingLabel.of("Audi", "A3", "35 TDI", "Audi Audi A3 très propre")).isEqualTo("Audi A3 35 TDI");
        assertThat(ListingLabel.of("Audi", "A3", null, "Audi A3")).isEqualTo("Audi A3");
        assertThat(ListingLabel.of("Audi", null, "  ", "Audi A3")).isEqualTo("Audi");
    }

    @Test
    @DisplayName("a model typed with its make in it does not name the make twice, in any case")
    void modelWithTheMake() {
        assertThat(ListingLabel.of("Audi", "Audi A3", "35 TDI", "t")).isEqualTo("Audi A3 35 TDI");
        assertThat(ListingLabel.of("AUDI", "audi a3", null, "t")).isEqualTo("AUDI a3");
        assertThat(ListingLabel.of("Audi", "Audition", null, "t")).isEqualTo("Audi Audition");
    }

    @Test
    @DisplayName("with no make, the seller's title stands in")
    void noMake() {
        assertThat(ListingLabel.of(null, "A3", "35 TDI", " Cabriolet de collection ")).isEqualTo("Cabriolet de collection");
        assertThat(ListingLabel.of(" ", null, null, null)).isEmpty();
    }
}
