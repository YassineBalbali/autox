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
            // EXPLAIN may add Crit'Air; a lone Crit'Air is the Crit'Air (second evaluation).
            Plan critAir = valid("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"crit_air\", \"ref\": \"L1\"}]}", "x");
            assertThat(critAir.intent()).isEqualTo(Plan.Intent.CRIT_AIR);
            assertThat(critAir.calls()).containsExactly(new Plan.CritAirOf(FIRST, null, null, null));
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
            // Neither a price nor a code, and no page's or single listing to fall back on.
            assertThat(validator.validate(JSON.readTree(base.formatted("\"months\": 48")), "x",
                    new References.Table(TABLE.references(), null), 2)).isEqualTo(new PlanValidator.Invalid("calls[0].amountEuros"));
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
            assertThat(invalid(base.formatted("\"fuel\": \"OTHER\", \"year\": 2015"), "x")).isEqualTo("calls[0].fuel");
            assertThat(invalid(base.formatted("\"fuel\": \"DIESEL\", \"year\": 1900"), "x")).isEqualTo("calls[0].year");
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
            // Another form of a word written is the user's word (second evaluation); another word is not.
            assertThat(valid(base.formatted("\"arrêter les mails d'alerte\""), "comment j'arrête les mails d'alerte ?").calls())
                    .containsExactly(new Plan.Help("arrêter les mails d'alerte"));
            assertThat(valid(base.formatted("\"annonce disparue\""), "Pourquoi mon annonce a disparu ?").calls())
                    .containsExactly(new Plan.Help("annonce disparue"));
            assertThat(invalid(base.formatted("\"vendre annonce\""), "Pourquoi mon annonce a disparu ?"))
                    .isEqualTo("calls[0].query");
            assertThat(invalid(base.formatted("\"" + "a ".repeat(101) + "\""), "a")).isEqualTo("calls[0].query");
            assertThat(invalid(base.formatted("null"), "x")).isEqualTo("calls[0].query");
        }
    }

    /** The plans of the second evaluation read for what they mean (docs/assistant-plan.md, 12). */
    @Nested
    class AfterTheSecondEvaluation {

        private final UUID only = UUID.randomUUID();
        private final References.Table one = new References.Table(List.of(
                new References.Reference("L1", only, "Peugeot 208, 2021, 38000 km, 11890 EUR, PETROL, MANUAL")), "L1");
        private final References.Table none = new References.Table(List.of(), null);
        private final References.Table several = new References.Table(List.of(
                new References.Reference("L1", FIRST, "Tesla Model 3, 2021, 50000 km, 24990 EUR, ELECTRIC, AUTOMATIC"),
                new References.Reference("L2", SECOND, "Tesla Model Y, 2022, 30000 km, 32990 EUR, ELECTRIC, AUTOMATIC")), null);

        private PlanValidator.Result check(String plan, String message, References.Table table) throws Exception {
            return validator.validate(JSON.readTree(plan), message, table, 2);
        }

        @Test
        @DisplayName("reads EXPLAIN with a lone crit_air as the Crit'Air")
        void intentOfTheCalls() throws Exception {
            Plan plan = ((PlanValidator.Valid) check("""
                    {"intent": "EXPLAIN", "calls": [{"tool": "crit_air", "fuel": "DIESEL", "year": 2011, "month": 1}]}""",
                    "Un diesel de janvier 2011, c'est quelle vignette ?", none)).plan();
            assertThat(plan.intent()).isEqualTo(Plan.Intent.CRIT_AIR);
            assertThat(plan.calls()).containsExactly(new Plan.CritAirOf(null, FuelType.DIESEL, 2011, 1));
            // A call the intent does not use at all is still a plan at odds with itself.
            assertThat(check("{\"intent\": \"SEARCH\", \"calls\": [{\"tool\": \"crit_air\", \"ref\": \"L1\"}]}", "x", one))
                    .isEqualTo(new PlanValidator.Invalid("calls[0].tool"));
        }

        @Test
        @DisplayName("runs a listing intent with no call on the listing meant, or asks the planner's question")
        void noCall() throws Exception {
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"FINANCE\", \"calls\": [], \"clarify\": \"BUDGET\"}",
                    "Ça ferait combien par mois ?", one)).plan().calls())
                    .containsExactly(new Plan.Finance(only, null, null, null, null));
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"COMPARE\", \"calls\": [], \"clarify\": \"WHICH_LISTING\"}",
                    "Compare les deux premières", one)).plan().calls())
                    .containsExactly(new Plan.TooFewToCompare(List.of(only)));
            // No listing meant: the question the planner asked.
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"FINANCE\", \"clarify\": \"BUDGET\"}", "x", several))
                    .plan().clarify()).isEqualTo(Plan.Question.BUDGET);
            assertThat(check("{\"intent\": \"FINANCE\"}", "x", several)).isEqualTo(new PlanValidator.Invalid("calls"));
        }

        @Test
        @DisplayName("asks for the car when a valuation or a described car's Crit'Air lacks a detail")
        void detailsMissing() throws Exception {
            Plan value = ((PlanValidator.Valid) check("""
                    {"intent": "VALUE", "calls": [{"tool": "estimate_value", "make": "Peugeot", "model": "208"}]}""",
                    "Ma 208, elle vaut combien ?", none)).plan();
            assertThat(value.intent()).isEqualTo(Plan.Intent.CLARIFY);
            assertThat(value.clarify()).isEqualTo(Plan.Question.VEHICLE);
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"CRIT_AIR\", \"calls\": [{\"tool\": \"crit_air\", \"year\": 2015}]}",
                    "x", none)).plan().clarify()).isEqualTo(Plan.Question.VEHICLE);
            // With no detail at all and a listing meant, it is that listing's.
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"CRIT_AIR\", \"calls\": [{\"tool\": \"crit_air\"}]}", "x", one))
                    .plan().calls()).containsExactly(new Plan.CritAirOf(only, null, null, null));
        }

        @Test
        @DisplayName("finances the listing whose price was copied, among several, when the message has another number")
        void priceCopiedAmongSeveral() throws Exception {
            String copied = "{\"intent\": \"FINANCE\", \"calls\": [{\"tool\": \"simulate_financing\", \"amountEuros\": 24990, \"months\": 60}]}";
            assertThat(((PlanValidator.Valid) check(copied, "Combien par mois pour la première sur 5 ans ?", several)).plan().calls())
                    .containsExactly(new Plan.Finance(FIRST, null, null, 60, null));
            // Written by the user, in any of the usual ways: their amount.
            for (String message : List.of("Et pour 24 990 € sur 5 ans ?", "Pour 24990 euros", "Pour 24.990 €")) {
                assertThat(((PlanValidator.Valid) check(copied, message, several)).plan().calls()).as(message)
                        .containsExactly(new Plan.Finance(null, 2_499_000L, null, 60, null));
            }
            assertThat(((PlanValidator.Valid) check(copied.replace("24990", "25000"), "Pour 25k sur 5 ans", several)).plan().calls())
                    .containsExactly(new Plan.Finance(null, 2_500_000L, null, 60, null));
        }
    }

    /** The fixes after the first trial on Groq (docs/assistant-plan.md, 12). */
    @Nested
    class AfterTheFirstTrial {

        private final UUID only = UUID.randomUUID();
        private final References.Table one = new References.Table(List.of(
                new References.Reference("L1", only, "Peugeot 208, 2021, 38000 km, 11890 EUR, PETROL, MANUAL")), null);
        private final References.Table none = new References.Table(List.of(), null);

        private PlanValidator.Result check(String plan, String message, References.Table table) throws Exception {
            return validator.validate(JSON.readTree(plan), message, table, 2);
        }

        @Test
        @DisplayName("finances the only listing known, or the page's, when no listing is named (bug 1)")
        void implicitListing() throws Exception {
            String finance = "{\"intent\": \"FINANCE\", \"calls\": [{\"tool\": \"simulate_financing\", \"downPaymentEuros\": 2000, \"months\": 48}]}";

            assertThat(check(finance, "Combien par mois sur 4 ans avec 2 000 € d'apport ?", one))
                    .isEqualTo(new PlanValidator.Valid(new Plan(Plan.Intent.FINANCE,
                            List.of(new Plan.Finance(only, null, 200_000L, 48, null)), null, null, List.of())));
            // Two listings and no page: which one is not for the server to guess.
            assertThat(check(finance, "x", new References.Table(TABLE.references(), null)))
                    .isEqualTo(new PlanValidator.Invalid("calls[0].amountEuros"));
            // The page's listing, among several.
            assertThat(((PlanValidator.Valid) check(finance, "x", TABLE)).plan().calls())
                    .containsExactly(new Plan.Finance(FIRST, null, 200_000L, 48, null));
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\"}]}", "x", one))
                    .plan().calls()).containsExactly(new Plan.OneListing(Plan.Tool.GET_LISTING, only));
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"CRIT_AIR\", \"calls\": [{\"tool\": \"crit_air\"}]}", "x", one))
                    .plan().calls()).containsExactly(new Plan.CritAirOf(only, null, null, null));
        }

        @Test
        @DisplayName("reads a listing code written loosely, and a blank one as none (first evaluation)")
        void looseCodes() throws Exception {
            for (String ref : List.of("\"l1\"", "\" L 1 \"")) {
                assertThat(((PlanValidator.Valid) check("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": "
                        + ref + "}]}", "x", one)).plan().calls()).as(ref).containsExactly(new Plan.OneListing(Plan.Tool.GET_LISTING, only));
            }
            // Blank: no code, so the listing meant, here the only one.
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"SIMILAR\", \"calls\": [{\"tool\": \"similar_listings\", \"ref\": \" \"}]}",
                    "x", one)).plan().calls()).containsExactly(new Plan.OneListing(Plan.Tool.SIMILAR_LISTINGS, only));
            // A code that names nothing is still refused.
            assertThat(check("{\"intent\": \"EXPLAIN\", \"calls\": [{\"tool\": \"get_listing\", \"ref\": \"L2\"}]}", "x", one))
                    .isEqualTo(new PlanValidator.Invalid("calls[0].ref"));
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"COMPARE\", \"calls\": [{\"tool\": \"compare_listings\", \"refs\": [\"l1\", \"L2 \"]}]}",
                    "x", TABLE)).plan().calls()).containsExactly(new Plan.Compare(List.of(FIRST, SECOND)));
        }

        @Test
        @DisplayName("finances the listing meant, not a price the user never wrote (first evaluation)")
        void priceNotWritten() throws Exception {
            String copied = "{\"intent\": \"FINANCE\", \"calls\": [{\"tool\": \"simulate_financing\", \"amountEuros\": 11890}]}";
            // « Ça ferait combien par mois ? » on the listing's page: the model copied its price from the label.
            assertThat(((PlanValidator.Valid) check(copied, "Ça ferait combien par mois ?", one)).plan().calls())
                    .containsExactly(new Plan.Finance(only, null, null, null, null));
            // A price the user wrote is theirs, listing or not.
            assertThat(((PlanValidator.Valid) check(copied, "Et pour 11 890 € ?", one)).plan().calls())
                    .containsExactly(new Plan.Finance(null, 1_189_000L, null, null, null));
            // No listing meant: the amount stands, whoever wrote it.
            assertThat(((PlanValidator.Valid) check(copied, "Combien par mois ?", none)).plan().calls())
                    .containsExactly(new Plan.Finance(null, 1_189_000L, null, null, null));
            // An amount in words is not the listing's price: it stands.
            assertThat(((PlanValidator.Valid) check(copied.replace("11890", "15000"), "Pour quinze mille euros ?", one)).plan().calls())
                    .containsExactly(new Plan.Finance(null, 1_500_000L, null, null, null));
        }

        @Test
        @DisplayName("has no clarifying question about financing terms: the simulator has defaults (bug 1)")
        void noFinancingQuestion() throws Exception {
            assertThat(check("{\"intent\": \"CLARIFY\", \"clarify\": \"FINANCING_TERMS\"}", "x", one))
                    .isEqualTo(new PlanValidator.Invalid("clarify"));
        }

        @Test
        @DisplayName("turns a comparison with fewer than two listings known into the 'only one' answer (bug 3)")
        void tooFewToCompare() throws Exception {
            String compare = "{\"intent\": \"COMPARE\", \"calls\": [{\"tool\": \"compare_listings\", \"refs\": [\"L1\", \"L2\"]}]}";
            assertThat(((PlanValidator.Valid) check(compare, "Compare les deux premières", one)).plan().calls())
                    .containsExactly(new Plan.TooFewToCompare(List.of(only)));
            assertThat(((PlanValidator.Valid) check(compare, "Compare les deux premières", none)).plan().calls())
                    .containsExactly(new Plan.TooFewToCompare(List.of()));

            // And when the planner asks "which listing?" to a comparison it cannot make.
            Plan asked = ((PlanValidator.Valid) check("{\"intent\": \"CLARIFY\", \"clarify\": \"WHICH_LISTING\"}",
                    "Compare les deux premières", one)).plan();
            assertThat(asked.intent()).isEqualTo(Plan.Intent.COMPARE);
            assertThat(asked.calls()).containsExactly(new Plan.TooFewToCompare(List.of(only)));
            // Not a comparison: the question stands.
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"CLARIFY\", \"clarify\": \"WHICH_LISTING\"}",
                    "Et celle en diesel ?", one)).plan().clarify()).isEqualTo(Plan.Question.WHICH_LISTING);
        }

        @Test
        @DisplayName("compares the two or three listings known when the planner names none")
        void compareTheKnown() throws Exception {
            assertThat(((PlanValidator.Valid) check("{\"intent\": \"COMPARE\", \"calls\": [{\"tool\": \"compare_listings\"}]}",
                    "Compare-les", new References.Table(TABLE.references(), null))).plan().calls())
                    .containsExactly(new Plan.Compare(List.of(FIRST, SECOND)));
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
