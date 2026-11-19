package com.autox.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The financing formula against the vectors the web and the app are held to, and the simulator's
 * constants against the web's own: one formula, three implementations, one set of expected figures.
 */
class CreditFormulaTest {

    private static final Path FIXTURE = Path.of("..", "frontend", "fixtures", "listing.json");
    private static final Path FORMULA = Path.of("..", "frontend", "src", "app", "shared", "listing", "financing.ts");
    private static final Path SIMULATOR = Path.of("..", "frontend", "src", "app", "features", "listing",
            "financing-simulator.ts");

    @Test
    @DisplayName("reproduces every simulateCredit vector in frontend/fixtures/listing.json")
    void sharedVectors() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Files.readString(FIXTURE)).get("simulateCredit");
        assertThat(vectors.size()).isGreaterThanOrEqualTo(10);

        for (JsonNode vector : vectors) {
            Optional<CreditFormula.Credit> credit = CreditFormula.simulate(vector.get("principal").asDouble(),
                    vector.get("annualRatePercent").asDouble(), vector.get("months").asInt());
            JsonNode expected = vector.get("result");
            if (expected.isNull()) {
                assertThat(credit).as(vector.get("name").asText()).isEmpty();
            } else {
                assertThat(credit).as(vector.get("name").asText()).contains(new CreditFormula.Credit(
                        expected.get("monthly").asLong(), expected.get("totalRepaid").asLong(),
                        expected.get("creditCost").asLong()));
            }
        }
    }

    @Test
    @DisplayName("has the web simulator's bounds, example rate and starting duration")
    void sameConstantsAsTheWeb() throws Exception {
        String formula = Files.readString(FORMULA);
        String simulator = Files.readString(SIMULATOR);

        assertThat(number(formula, "minMonths: (\\d+)")).isEqualTo(String.valueOf(CreditFormula.MIN_MONTHS));
        assertThat(number(formula, "maxMonths: (\\d+)")).isEqualTo(String.valueOf(CreditFormula.MAX_MONTHS));
        assertThat(Double.parseDouble(number(formula, "maxAnnualRatePercent: ([\\d.]+)")))
                .isEqualTo(CreditFormula.MAX_ANNUAL_RATE_PERCENT);
        assertThat(Double.parseDouble(number(simulator, "EXAMPLE_RATE_PERCENT = ([\\d.]+)")))
                .isEqualTo(FinancingTool.EXAMPLE_RATE_PERCENT);
        assertThat(number(simulator, "months = signal\\((\\d+)\\)")).isEqualTo(String.valueOf(FinancingTool.DEFAULT_MONTHS));
    }

    private static String number(String source, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(source);
        assertThat(matcher.find()).as(pattern).isTrue();
        return matcher.group(1);
    }
}
