package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.assistant.tools.CompareListingsTool;
import com.autox.assistant.tools.CreditFormula;
import com.autox.assistant.tools.FinancingTool;
import com.autox.assistant.tools.ValuationTool;
import com.autox.catalog.FuelType;
import com.autox.search.SearchSort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each row of docs/assistant-plan.md, 2.2, fed hostile plans as a model could write them. */
class PlanValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final References.Table TABLE = new References.Table(List.of(
            new References.Reference("L1", FIRST, "Peugeot 208, 2020, 40000 km, 10000 EUR, PETROL, MANUAL"),
            new References.Reference("L2", SECOND, "Peugeot 208, 2019, 60000 km, 9000 EUR, PETROL, MANUAL")), "L1");

    private final PlanValidator validator = new PlanValidator();

    private PlanValidator.Result check(String plan, String message) throws Exception {
        return validator.validate(JSON.readTree(plan), message, TABLE, 2);
    }

    private Plan valid(String plan, String message) throws Exception {
        PlanValidator.Result result = check(plan, message);
        assertThat(result).isInstanceOf(PlanValidator.Valid.class);
        return ((PlanValidator.Valid) result).plan();
    }

    private String invalid(String plan, String message) throws Exception {
        PlanValidator.Result result = check(plan, message);
        assertThat(result).isInstanceOf(PlanValidator.Invalid.class);
        return ((PlanValidator.Invalid) result).field();
    }

    @Nested
    class TheWholePlan {

        @Test
        @DisplayName("refuses what is not an object, an intent that is not ours, and a call its intent does not use")
        void shape() throws Exception {
            assertThat(validator.validate(JSON.readTree("[]"), "x", TABLE, 2)).isEqualTo(new PlanValidator.Invalid("plan"));
            assertThat(invalid("{\"intent\": \"POEM\"}", "x")).isEqualTo("intent");
            assertThat(invalid("{\"intent\": \"search\", \"calls\": []}", "x")).isEqualTo("intent");
            assertThat(invalid("""
                    {"intent": "SEARCH", "calls": [{"tool": "simulate_financing", "amountEuros": 10000}]}""", "x"))
                    .isEqualTo("calls[0].tool");
            assertThat(invalid("{\"intent\": \"SEARCH\", \"calls\": [{\"tool\": \"send_email\"}]}", "x"))
                    .isEqualTo("calls[0].tool");
        }

        @Test
        @DisplayName("needs one to three calls, the intent's own among them")
        void calls() throws Exception {
            assertThat(invalid("{\"intent\": \"SEARCH\"}", "x")).isEqualTo("calls");
            assertThat(invalid("{\"intent\": \"SEARCH\", \"calls\": []}", "x")).isEqualTo("calls");
            String four = "{\"tool\": \"get_listing\", \"ref\": \"L1\"}";
            assertThat(invalid("{\"intent\": \"EXPLAIN\", \"calls\": [%s, %s, %s, %s]}".formatted(four, four, four, four), "x"))
                    .isEqualTo("calls");
            // EXPLAIN may add Crit'Air, but needs the listing itself.
            assertThat(invalid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"crit_air\", \"ref\": \"L1\"}]}", "x"))
                    .isEqualTo("calls");
        }

        @Test
        @DisplayName("takes a no-answer only with a reason the planner may give, and a question from the list")
        void noAnswerAndClarify() throws Exception {
            assertThat(valid("{\"intent\": \"NO_ANSWER\", \"noAnswer\": \"OUT_OF_SCOPE\", \"calls\": null}", "x").noAnswer())
                    .isEqualTo(Plan.Reason.OUT_OF_SCOPE);
            assertThat(invalid("{\"intent\": \"NO_ANSWER\", \"noAnswer\": \"NO_RESULTS\"}", "x")).isEqualTo("noAnswer");
            assertThat(invalid("{\"intent\": \"NO_ANSWER\", \"noAnswer\": \"ADVICE\", \"calls\": [{\"tool\": \"search_help\"}]}", "x"))
                    .isEqualTo("calls");
            assertThat(valid("{\"intent\": \"CLARIFY\", \"clarify\": \"BUDGET\"}", "x").clarify()).isEqualTo(Plan.Question.BUDGET);
            assertThat(invalid("{\"intent\": \"CLARIFY\", \"clarify\": \"What is your budget?\"}", "x")).isEqualTo("clarify");
        }

        @Test
        @DisplayName("keeps a not-understood quotation only when the message contains it")
        void quotations() throws Exception {
            Plan plan = valid("""
                    {"intent": "CLARIFY", "clarify": "VEHICLE", "notUnderstood": ["première main", "toit ouvrant", 3]}""",
                    "Une voiture de Première  main");
            assertThat(plan.notUnderstood()).containsExactly("première main");
        }
    }

    @Nested
    class References_ {

        @Test
        @DisplayName("maps a reference to the id the server holds, and refuses one that is not in the table")
        void refs() throws Exception {
            Plan plan = valid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": \"L2\"}]}", "x");
            assertThat(plan.calls()).containsExactly(new Plan.OneListing(Plan.Tool.GET_LISTING, SECOND));

            assertThat(invalid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": \"L3\"}]}", "x"))
                    .isEqualTo("calls[0].ref");
            assertThat(invalid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": \"" + FIRST + "\"}]}", "x"))
                    .isEqualTo("calls[0].ref");
            assertThat(invalid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": 1}]}", "x"))
                    .isEqualTo("calls[0].ref");
        }

        @Test
        @DisplayName("compares two or three distinct known listings, and nothing else")
        void compare() throws Exception {
            assertThat(valid("{\"intent\": \"COMPARE\", \"calls\": [{\"tool\": \"compare_listings\", \"refs\": [\"L2\", \"L1\"]}]}", "x")
                    .calls()).containsExactly(new Plan.Compare(List.of(SECOND, FIRST)));
            for (String refs : List.of("[\"L1\"]", "[\"L1\", \"L1\"]", "[\"L1\", \"L9\"]", "\"L1,L2\"",
                    "[\"L1\", \"L2\", \"L1\", \"L2\"]")) {
                assertThat(invalid("{\"intent\": \"COMPARE\", \"calls\": [{\"tool\": \"compare_listings\", \"refs\": " + refs + "}]}", "x"))
                        .as(refs).isEqualTo("calls[0].refs");
            }
        }
    }

    @Nested
    class Financing {

        private Plan.Finance finance(String args) throws Exception {
            Plan plan = valid("{\"intent\": \"FINANCE\", \"calls\": [{\"tool\": \"simulate_financing\", " + args + "}]}", "x");
            return (Plan.Finance) plan.calls().getFirst();
        }

        @Test
        @DisplayName("takes a listing, or a price in euros turned into cents, and the terms")
        void terms() throws Exception {
            assertThat(finance("\"ref\": \"L1\", \"downPaymentEuros\": 2000, \"months\": 60, \"ratePercent\": 4.5"))
                    .isEqualTo(new Plan.Finance(FIRST, null, 200_000L, 60, 4.5));
            assertThat(finance("\"amountEuros\": 15000"))
                    .isEqualTo(new Plan.Finance(null, 1_500_000L, null, null, null));
        }

        @Test
        @DisplayName("drops a duration not by year or out of 12–84, a rate out of 0–30 %, a negative down payment")
        void dropped() throws Exception {
            assertThat(finance("\"ref\": \"L1\", \"months\": 30, \"ratePercent\": 45, \"downPaymentEuros\": -5"))
                    .isEqualTo(new Plan.Finance(FIRST, null, null, null, null));
            assertThat(finance("\"ref\": \"L1\", \"months\": 96, \"ratePercent\": -1"))
                    .isEqualTo(new Plan.Finance(FIRST, null, null, null, null));
            // The wrong type is absent, never coerced.
            assertThat(finance("\"ref\": \"L1\", \"months\": \"48\", \"downPaymentEuros\": 1000.5"))
                    .isEqualTo(new Plan.Finance(FIRST, null, null, null, null));
        }

        @Test
        @DisplayName("refuses neither a listing nor a price, a price out of bounds, and an unknown listing")
        void refused() throws Exception {
            String base = "{\"intent\": \"FINANCE\", \"calls\": [{\"tool\": \"simulate_financing\", %s}]}";
            assertThat(invalid(base.formatted("\"months\": 48"), "x")).isEqualTo("calls[0].amountEuros");
            assertThat(invalid(base.formatted("\"amountEuros\": 50"), "x")).isEqualTo("calls[0].amountEuros");
            assertThat(invalid(base.formatted("\"amountEuros\": 20000000"), "x")).isEqualTo("calls[0].amountEuros");
            assertThat(invalid(base.formatted("\"ref\": \"L7\", \"amountEuros\": 10000"), "x")).isEqualTo("calls[0].ref");
        }
    }

    @Nested
    class ValueAndCritAir {

        private static final String VALUE = """
                {"intent": "VALUE", "calls": [{"tool": "estimate_value", "make": "Peugeot", "model": "208",
                 "fuel": "PETROL", "powerHp": %s, "year": %s, "month": %s, "mileageKm": %s}]}""";

        @Test
        @DisplayName("needs the model, fuel, power, year and mileage in their bounds, and drops a bad month")
        void value() throws Exception {
            Plan.Value value = (Plan.Value) valid(VALUE.formatted(100, 2020, 13, 40000), "x").calls().getFirst();
            assertThat(value).isEqualTo(new Plan.Value("Peugeot", "208", FuelType.PETROL, 100, 2020, null, 40000, null));

            assertThat(invalid(VALUE.formatted(5, 2020, 1, 40000), "x")).isEqualTo("calls[0].powerHp");
            assertThat(invalid(VALUE.formatted(100, 1900, 1, 40000), "x")).isEqualTo("calls[0].year");
            assertThat(invalid(VALUE.formatted(100, Year.now().getValue() + 2, 1, 40000), "x")).isEqualTo("calls[0].year");
            assertThat(invalid(VALUE.formatted(100, 2020, 1, 2_000_000), "x")).isEqualTo("calls[0].mileageKm");
            assertThat(invalid(VALUE.replace("PETROL", "OTHER").formatted(100, 2020, 1, 40000), "x")).isEqualTo("calls[0].fuel");
        }

        @Test
        @DisplayName("reads a listing's Crit'Air, or a described car's from a fuel and a year")
        void critAir() throws Exception {
            String base = "{\"intent\": \"CRIT_AIR\", \"calls\": [{\"tool\": \"crit_air\", %s}]}";
            assertThat(valid(base.formatted("\"ref\": \"L1\""), "x").calls())
                    .containsExactly(new Plan.CritAirOf(FIRST, null, null, null));
            assertThat(valid(base.formatted("\"fuel\": \"DIESEL\", \"year\": 2015, \"month\": 4"), "x").calls())
                    .containsExactly(new Plan.CritAirOf(null, FuelType.DIESEL, 2015, 4));
            assertThat(invalid(base.formatted("\"year\": 2015"), "x")).isEqualTo("calls[0].fuel");
            assertThat(invalid(base.formatted("\"fuel\": \"DIESEL\""), "x")).isEqualTo("calls[0].year");
        }
    }

    @Nested
    class SearchAndHelp {

        @Test
        @DisplayName("takes a criteria object and a known sort, leaving the criteria to the smart search's validator")
        void search() throws Exception {
            Plan.Search search = (Plan.Search) valid("""
                    {"intent": "SEARCH", "calls": [{"tool": "search_listings", "criteria": {"make": "peugeot"}, "sort": "PRICE_ASC"}]}""",
                    "x").calls().getFirst();
            assertThat(search.criteria().get("make").asText()).isEqualTo("peugeot");
            assertThat(search.sort()).isEqualTo(SearchSort.PRICE_ASC);
            assertThat(invalid("{\"intent\": \"SEARCH\", \"calls\": [{\"tool\": \"search_listings\", \"criteria\": \"peugeot\"}]}", "x"))
                    .isEqualTo("calls[0].criteria");
        }

        @Test
        @DisplayName("searches the help pages only with the user's own words")
        void help() throws Exception {
            String base = "{\"intent\": \"HELP\", \"calls\": [{\"tool\": \"search_help\", \"query\": %s}]}";
            assertThat(valid(base.formatted("\"supprimer compte\""), "Comment SUPPRIMER mon compte ?").calls())
                    .containsExactly(new Plan.Help("supprimer compte"));
            assertThat(invalid(base.formatted("\"supprimer annonce\""), "Comment supprimer mon compte ?"))
                    .isEqualTo("calls[0].query");
            assertThat(invalid(base.formatted("\"" + "a ".repeat(101) + "\""), "a")).isEqualTo("calls[0].query");
            assertThat(invalid(base.formatted("null"), "x")).isEqualTo("calls[0].query");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "24", "36", "48", "60", "72", "84"})
    @DisplayName("lets through only durations the financing tool accepts")
    void durationsInsideTheTool(String months) throws Exception {
        Plan.Finance finance = (Plan.Finance) valid("""
                {"intent": "FINANCE", "calls": [{"tool": "simulate_financing", "ref": "L1", "months": %s}]}""".formatted(months),
                "x").calls().getFirst();
        assertThat(finance.months()).isBetween(CreditFormula.MIN_MONTHS, CreditFormula.MAX_MONTHS);
        assertThat(finance.months() % 12).isZero();
    }

    @Test
    @DisplayName("has bounds inside the tools' own, so a valid plan never makes a tool throw")
    void boundsInsideTheTools() {
        assertThat(PlanValidator.MIN_AMOUNT_EUROS * 100).isGreaterThanOrEqualTo(FinancingTool.MIN_AMOUNT);
        assertThat(PlanValidator.MAX_AMOUNT_EUROS * 100).isLessThanOrEqualTo(FinancingTool.MAX_AMOUNT);
        assertThat(PlanValidator.MIN_POWER_HP).isGreaterThanOrEqualTo(ValuationTool.MIN_POWER_HP);
        assertThat(PlanValidator.MAX_POWER_HP).isLessThanOrEqualTo(ValuationTool.MAX_POWER_HP);
        assertThat(PlanValidator.MIN_YEAR).isGreaterThanOrEqualTo(ValuationTool.MIN_YEAR);
        assertThat(PlanValidator.MAX_MILEAGE_KM).isLessThanOrEqualTo(ValuationTool.MAX_MILEAGE_KM);
        assertThat(PlanValidator.MAX_CALLS).isEqualTo(3);
        assertThat(CompareListingsTool.MAX_LISTINGS).isEqualTo(3);
    }

    @Test
    @DisplayName("never coerces: a string where a number belongs is absent")
    void noCoercion() throws Exception {
        JsonNode plan = JSON.readTree("""
                {"intent": "FINANCE", "calls": [{"tool": "simulate_financing", "amountEuros": "15000"}]}""");
        assertThat(validator.validate(plan, "x", TABLE, 2)).isEqualTo(new PlanValidator.Invalid("calls[0].amountEuros"));
    }
}
