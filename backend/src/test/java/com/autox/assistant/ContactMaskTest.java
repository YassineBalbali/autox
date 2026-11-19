package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Contact details never leave for the provider; prices, years and mileages do. */
class ContactMaskTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "écrivez-moi à jean.dupont+voiture@exemple.fr svp",
            "mon numéro 06 12 34 56 78",
            "appelez le 0612345678",
            "ou le +33 6 12 34 56 78",
            "ou le 0033612345678",
            "ou le +33 (0)6 12 34 56 78",
            "ou le 06.12.34.56.78",
            "à l'étranger +44 20 7946 0958",
    })
    void removes(String message) {
        ContactMask.Masked masked = ContactMask.mask(message);

        assertThat(masked.removed()).isTrue();
        assertThat(masked.text()).contains(ContactMask.REPLACEMENT).doesNotContainPattern("\\d{2}[ .]?\\d{2}[ .]?\\d{2}[ .]?\\d{2}");
        assertThat(masked.text()).doesNotContain("@");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "une 208 de 2019 à moins de 12 000 €",
            "entre 10 000 et 15 000 € et moins de 150 000 km",
            "budget 10 000 2019 ou plus récente",
            "Crit'Air 1, 48 mois à 5,9 %",
    })
    void keeps(String message) {
        assertThat(ContactMask.mask(message)).isEqualTo(new ContactMask.Masked(message, false));
    }
}
