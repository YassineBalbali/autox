package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.catalog.FuelType;
import com.autox.search.ValuationScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The eval header: the checked plan in the planner's own terms, the answer's reasons, the tokens. */
class EvalTraceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final References.Table table = new References.Table(List.of(
            new References.Reference("L1", first, "Peugeot 208"), new References.Reference("L2", second, "Clio")), null);

    @Test
    @DisplayName("writes listings as their codes and amounts in whole euros, as the planner does")
    void plannerTerms() throws Exception {
        Plan plan = new Plan(Plan.Intent.FINANCE, List.of(new Plan.Finance(second, null, 200_000L, 48, null)),
                null, null, List.of());
        List<AssistantBlock> blocks = List.of(AssistantBlock.noAnswer(Plan.Reason.SELLER_CONTACT, second));
        AnswerBuilder.Answer answer = new AnswerBuilder.Answer(blocks, List.of(second), List.of(Plan.Reason.SELLER_CONTACT));

        JsonNode trace = json.readTree(EvalTrace.of(plan, answer, blocks, table, 2, List.of(), 1, 1_700, 90, json));

        assertThat(trace.get("intent").asText()).isEqualTo("FINANCE");
        assertThat(trace.at("/calls/0/tool").asText()).isEqualTo("simulate_financing");
        assertThat(trace.at("/calls/0/ref").asText()).isEqualTo("L2");
        assertThat(trace.at("/calls/0/downPaymentEuros").asInt()).isEqualTo(2000);
        assertThat(trace.at("/calls/0/months").asInt()).isEqualTo(48);
        assertThat(trace.at("/calls/0").has("ratePercent")).isFalse();
        assertThat(trace.get("reasons").get(0).asText()).isEqualTo("SELLER_CONTACT");
        assertThat(trace.get("blocks").get(0).asText()).isEqualTo("NO_ANSWER");
        assertThat(trace.get("tokensIn").asLong()).isEqualTo(1_700);
        assertThat(trace.get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("is ASCII, which a header requires: other characters are escaped")
    void ascii() throws Exception {
        Plan plan = new Plan(Plan.Intent.VALUE, List.of(new Plan.Value("Citroën", "C3", FuelType.PETROL, 83, 2019, null,
                50_000, ValuationScope.EXACT)), null, null, List.of());
        AnswerBuilder.Answer answer = new AnswerBuilder.Answer(List.of(), List.of(), List.of());

        String header = EvalTrace.of(plan, answer, List.of(), table, 2, List.of(), 1, 0, 0, json);

        assertThat(header).matches("\\p{ASCII}+").contains("Citro\\u00EBn");
        assertThat(json.readTree(header).at("/calls/0/make").asText()).isEqualTo("Citroën");
    }

    @Test
    @DisplayName("writes two refused plans as the « reformuler » answer, with no intent")
    void invalid() throws Exception {
        JsonNode trace = json.readTree(EvalTrace.invalid(List.of(new EvalTrace.Rejected("intent", json.readTree("{\"intent\": \"search\"}")), new EvalTrace.Rejected("timeout", null)), 2, 3_000, 200, json));
        assertThat(trace.get("intent").isNull()).isTrue();
        assertThat(trace.get("reasons").get(0).asText()).isEqualTo("REPHRASE");
        assertThat(trace.get("attempts").asInt()).isEqualTo(2);
        assertThat(trace.at("/rejected/0/field").asText()).isEqualTo("intent");
        assertThat(trace.at("/rejected/0/plan/intent").asText()).isEqualTo("search");
        assertThat(trace.at("/rejected/1/field").asText()).isEqualTo("timeout");
        assertThat(trace.at("/rejected/1").has("plan")).isFalse();
    }
}
