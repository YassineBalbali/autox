package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * eval/assistant/cases.json, the planner's evaluation set, checked without a model: every expected
 * plan is one {@link PlanValidator} accepts, with as many listings in memory as the case has, and
 * every code it names is one of ours. A plan the server would refuse could never be matched, so an
 * expectation like that would only measure the set. The model itself is evaluated by hand, by
 * eval/assistant/run-eval.mjs against a local API (docs/assistant-plan.md, 9); never here.
 *
 * <p>eval/assistant/heldout.json, the owner's own questions, is checked the same way, case by case,
 * once it has some: empty, it only has to be a set.
 */
class AssistantEvalSetTest {

    static final Path CASES = Path.of("..", "eval", "assistant", "cases.json");
    static final Path HELDOUT = Path.of("..", "eval", "assistant", "heldout.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PlanValidator VALIDATOR = new PlanValidator();
    private static final Set<String> EXPECT_FIELDS = Set.of("intent", "calls", "extraCalls", "noAnswer", "clarify",
            "reasons", "notReasons", "blocks", "notBlocks", "anyOf");
    /** The criteria fields the prompt lists, AssistantPrompt#system. */
    private static final Set<String> CRITERIA = Set.of("make", "model", "yearFrom", "yearTo", "priceMin", "priceMax",
            "mileageMin", "mileageMax", "fuelType", "gearbox", "bodyType", "condition", "offer", "leasingType",
            "monthlyMin", "monthlyMax", "durationMin", "durationMax", "mileageAllowanceMin", "damage", "location",
            "goodDeals");
    private static final Pattern RELATIVE_YEAR = Pattern.compile("now-\\d{1,2}");
    private static final int THIS_YEAR = java.time.Year.now().getValue();

    /** One expectation of one turn, an alternative of anyOf counting as its own. */
    record Expectation(String caseId, int turn, String message, int known, boolean page, JsonNode expect) {
        @Override
        public String toString() {
            return caseId + " #" + (turn + 1);
        }
    }

    static JsonNode set() throws IOException {
        return JSON.readTree(Files.readString(CASES));
    }

    static JsonNode heldOut() throws IOException {
        return JSON.readTree(Files.readString(HELDOUT));
    }

    static Stream<Expectation> expectations() throws IOException {
        List<Expectation> all = new ArrayList<>();
        List<JsonNode> cases = new ArrayList<>();
        set().get("cases").forEach(cases::add);
        heldOut().get("cases").forEach(cases::add);
        for (JsonNode c : cases) {
            JsonNode setup = c.path("setup");
            int seeded = setup.path("listings").path("count").asInt(0);
            boolean page = setup.path("page").asBoolean(false);
            JsonNode turns = c.get("turns");
            for (int i = 0; i < turns.size(); i++) {
                JsonNode turn = turns.get(i);
                int known = turn.has("known") ? turn.get("known").asInt() : seeded;
                JsonNode expect = turn.get("expect");
                if (expect.has("anyOf")) {
                    for (JsonNode alternative : expect.get("anyOf")) {
                        all.add(new Expectation(c.get("id").asText(), i, turn.get("message").asText(), known, page,
                                alternative));
                    }
                } else {
                    all.add(new Expectation(c.get("id").asText(), i, turn.get("message").asText(), known, page, expect));
                }
            }
        }
        return all.stream();
    }

    @Test
    @DisplayName("has at least 60 cases with unique ids, each with a category and turns the API would take")
    void shape() throws IOException {
        JsonNode cases = set().get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(60);
        shape(cases, new HashSet<>());
    }

    @Test
    @DisplayName("has a held-out set of the same shape, empty until the owner fills it, with ids of its own")
    void heldOutShape() throws IOException {
        JsonNode set = heldOut();
        assertThat(set.path("version").isInt()).isTrue();
        assertThat(set.get("cases").isArray()).isTrue();
        Set<String> ids = new HashSet<>();
        set().get("cases").forEach(c -> ids.add(c.path("id").asText()));
        shape(set.get("cases"), ids);
    }

    /** @param ids taken already: a case's id must be new to them */
    private static void shape(JsonNode cases, Set<String> ids) {
        for (JsonNode c : cases) {
            String id = c.path("id").asText();
            assertThat(id).matches("[a-z0-9]+(-[a-z0-9]+)*");
            assertThat(ids.add(id)).as("unique id %s", id).isTrue();
            assertThat(c.path("category").asText()).as(id).isNotBlank();
            assertThat(c.get("turns")).as(id).isNotEmpty();
            assertThat(c.get("turns").size()).as(id).isLessThanOrEqualTo(TurnRequest.MAX_TURNS);
            for (JsonNode turn : c.get("turns")) {
                assertThat(turn.path("message").asText()).as(id).isNotBlank().hasSizeLessThanOrEqualTo(TurnRequest.MAX_MESSAGE);
                assertThat(turn.get("expect")).as(id + " has an expected outcome").isNotNull();
                turn.get("expect").fieldNames().forEachRemaining(field -> assertThat(EXPECT_FIELDS).as(id).contains(field));
            }
            int seeded = c.path("setup").path("listings").path("count").asInt(0);
            assertThat(seeded).as(id).isBetween(0, References.MAX);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectations")
    @DisplayName("expects a plan the validator accepts, and codes that exist")
    void accepted(Expectation e) {
        JsonNode expect = e.expect();
        for (String field : List.of("reasons", "notReasons")) {
            expect.path(field).forEach(reason -> assertThat(Plan.Reason.valueOf(reason.asText())).isNotNull());
        }
        for (String field : List.of("blocks", "notBlocks")) {
            expect.path(field).forEach(type -> assertThat(AssistantBlock.Type.valueOf(type.asText())).isNotNull());
        }
        assertThat(expect.has("intent") || expect.has("reasons") || expect.has("blocks"))
                .as("%s expects something", e).isTrue();
        if (!expect.has("intent")) {
            assertThat(expect.has("calls") || expect.has("noAnswer") || expect.has("clarify")).as("%s", e).isFalse();
            return;
        }
        List<String> intents = new ArrayList<>();
        if (expect.get("intent").isArray()) {
            expect.get("intent").forEach(intent -> intents.add(intent.asText()));
        } else {
            intents.add(expect.get("intent").asText());
        }
        for (String intent : intents) {
            Plan.Intent expected = Plan.Intent.valueOf(intent);
            ObjectNode plan = plannerPlan(expected, expect);
            PlanValidator.Result result = VALIDATOR.validate(plan, e.message(), table(e.known(), e.page()), 2);
            assertThat(result).as("%s: %s", e, plan).isInstanceOf(PlanValidator.Valid.class);
            assertThat(((PlanValidator.Valid) result).plan().intent()).as("%s", e).isEqualTo(expected);
        }
        expect.path("calls").forEach(call -> {
            JsonNode criteria = call.get("criteria");
            if (criteria != null) {
                criteria.fieldNames().forEachRemaining(field -> assertThat(CRITERIA).as("%s criteria", e).contains(field));
                for (String year : List.of("yearFrom", "yearTo")) {
                    JsonNode value = criteria.get(year);
                    if (value != null && value.isTextual()) {
                        assertThat(value.asText()).as("%s %s", e, year).matches(RELATIVE_YEAR);
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("covers every intent, every reason the planner gives, every question, and the server's own refusals")
    void coverage() throws IOException {
        Set<Plan.Intent> intents = EnumSet.noneOf(Plan.Intent.class);
        Set<Plan.Reason> reasons = EnumSet.noneOf(Plan.Reason.class);
        Set<Plan.Question> questions = EnumSet.noneOf(Plan.Question.class);
        expectations().forEach(e -> {
            JsonNode intent = e.expect().path("intent");
            (intent.isArray() ? intent : JSON.createArrayNode().add(intent)).forEach(value -> {
                if (value.isTextual()) {
                    intents.add(Plan.Intent.valueOf(value.asText()));
                }
            });
            if (e.expect().hasNonNull("noAnswer")) {
                reasons.add(Plan.Reason.valueOf(e.expect().get("noAnswer").asText()));
            }
            e.expect().path("reasons").forEach(reason -> reasons.add(Plan.Reason.valueOf(reason.asText())));
            if (e.expect().hasNonNull("clarify")) {
                questions.add(Plan.Question.valueOf(e.expect().get("clarify").asText()));
            }
        });
        assertThat(intents).containsExactlyInAnyOrder(Plan.Intent.values());
        assertThat(reasons).containsAll(Plan.PLANNER_REASONS).contains(Plan.Reason.SELLER_CONTACT,
                Plan.Reason.NOT_ENOUGH_LISTINGS, Plan.Reason.NOT_FINANCEABLE, Plan.Reason.NOT_IN_HELP);
        assertThat(questions).containsExactlyInAnyOrder(Plan.Question.values());

        List<String> categories = new ArrayList<>();
        int multiTurn = 0;
        for (JsonNode c : set().get("cases")) {
            categories.add(c.get("category").asText());
            multiTurn += c.get("turns").size() > 1 ? 1 : 0;
        }
        assertThat(categories).contains("search", "explain", "compare", "similar", "finance", "crit_air", "value",
                "help", "off_topic", "seller_contact", "injection", "contact_details", "informal", "multi_turn");
        assertThat(multiTurn).isGreaterThanOrEqualTo(8);
    }

    /** The expectation as the planner would write it: only the plan's own fields, nulls left out. */
    private static ObjectNode plannerPlan(Plan.Intent intent, JsonNode expect) {
        ObjectNode plan = JSON.createObjectNode().put("intent", intent.name());
        if (expect.hasNonNull("noAnswer")) {
            plan.put("noAnswer", expect.get("noAnswer").asText());
        }
        if (expect.hasNonNull("clarify")) {
            plan.put("clarify", expect.get("clarify").asText());
        }
        if (expect.has("calls")) {
            ArrayNode calls = plan.putArray("calls");
            for (JsonNode call : expect.get("calls")) {
                ObjectNode written = calls.addObject();
                call.fields().forEachRemaining(field -> {
                    if (!field.getValue().isNull()) {
                        written.set(field.getKey(), field.getValue().isObject() ? resolved(field.getValue()) : field.getValue());
                    }
                });
            }
        } else if (intent != Plan.Intent.NO_ANSWER && intent != Plan.Intent.CLARIFY) {
            // A COMPARE expected only by its reason (too few listings): any comparison will do.
            plan.putArray("calls").addObject().put("tool", Plan.PRIMARY.get(intent).wireName());
        }
        return plan;
    }

    /** Criteria with relative years made absolute, nulls left out. */
    private static JsonNode resolved(JsonNode criteria) {
        ObjectNode out = JSON.createObjectNode();
        criteria.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value.isNull()) {
                return;
            }
            if (value.isTextual() && RELATIVE_YEAR.matcher(value.asText()).matches()) {
                out.put(field.getKey(), THIS_YEAR - Integer.parseInt(value.asText().substring(4)));
            } else {
                out.set(field.getKey(), value);
            }
        });
        return out;
    }

    /** As many listings as the case has in memory, L1 first; the page's the last of them. */
    private static References.Table table(int known, boolean page) {
        List<References.Reference> references = new ArrayList<>();
        for (int i = 1; i <= known; i++) {
            references.add(new References.Reference("L" + i, UUID.randomUUID(), "Peugeot 208, 2020"));
        }
        return new References.Table(references, page && known > 0 ? "L" + known : null);
    }
}
