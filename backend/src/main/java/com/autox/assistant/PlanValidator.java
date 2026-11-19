package com.autox.assistant;

import com.autox.assistant.tools.CompareListingsTool;
import com.autox.assistant.tools.CreditFormula;
import com.autox.assistant.tools.HelpIndex;
import com.autox.assistant.tools.ValuationTool;
import com.autox.catalog.FuelType;
import com.autox.search.SearchSort;
import com.autox.search.ValuationScope;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the planner's JSON defensively and checks every value before any tool runs
 * (docs/assistant-plan.md, 2.2), as {@code InterpretationValidator} does for the smart search. A
 * value of the wrong type is absent, never coerced.
 *
 * <p>Two kinds of failure. <b>A plan that cannot be followed</b> is invalid, and the turn tries the
 * planner once more: an intent that is not one of ours, a tool the intent does not use, a listing
 * reference that is not in the turn's table, a help query with words the user did not write, a
 * required argument missing. <b>A value out of bounds</b> in an otherwise sound call is dropped, and
 * the tool's default applies: a 30-month loan becomes the default 48, a 45 % rate the example rate.
 *
 * <p>The search criteria are not checked here: the {@code search_listings} tool runs them through
 * the smart search's own validator, which reports what it dropped.
 */
@Component
public class PlanValidator {

    public static final int MAX_CALLS = 3;
    public static final int MAX_NOT_UNDERSTOOD = 6;
    /** In euros, as the planner writes them: the smart search's bounds for a price. */
    public static final long MIN_AMOUNT_EUROS = 100;
    public static final long MAX_AMOUNT_EUROS = 10_000_000;
    public static final int MIN_POWER_HP = ValuationTool.MIN_POWER_HP;
    public static final int MAX_POWER_HP = ValuationTool.MAX_POWER_HP;
    public static final int MIN_YEAR = 1950;
    public static final int MAX_MILEAGE_KM = 1_000_000;

    private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");

    /** The outcome: a plan to follow, or the first field that made it impossible to follow. */
    public sealed interface Result {
    }

    public record Valid(Plan plan) implements Result {
    }

    /** @param field where, as {@code calls[0].ref}: logged, never with its value */
    public record Invalid(String field) implements Result {
    }

    /**
     * @param minorUnit decimal places of the euro, for amounts the planner writes in euros
     */
    public Result validate(JsonNode answer, String message, References.Table references, int minorUnit) {
        if (answer == null || !answer.isObject()) {
            return new Invalid("plan");
        }
        Plan.Intent intent = enumOf(Plan.Intent.class, text(answer, "intent"));
        if (intent == null) {
            return new Invalid("intent");
        }
        List<String> notUnderstood = quotations(answer.get("notUnderstood"), message);

        if (intent == Plan.Intent.NO_ANSWER) {
            Plan.Reason reason = enumOf(Plan.Reason.class, text(answer, "noAnswer"));
            if (reason == null || !Plan.PLANNER_REASONS.contains(reason)) {
                return new Invalid("noAnswer");
            }
            return noCalls(answer) ? new Valid(new Plan(intent, List.of(), reason, null, notUnderstood))
                    : new Invalid("calls");
        }
        if (intent == Plan.Intent.CLARIFY) {
            Plan.Question question = enumOf(Plan.Question.class, text(answer, "clarify"));
            if (question == null) {
                return new Invalid("clarify");
            }
            return noCalls(answer) ? new Valid(new Plan(intent, List.of(), null, question, notUnderstood))
                    : new Invalid("calls");
        }

        JsonNode calls = answer.get("calls");
        if (calls == null || !calls.isArray() || calls.isEmpty() || calls.size() > MAX_CALLS) {
            return new Invalid("calls");
        }
        long scale = Math.round(Math.pow(10, minorUnit));
        List<Plan.Call> checked = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            String at = "calls[" + i + "]";
            JsonNode call = calls.get(i);
            Plan.Tool tool = call != null && call.isObject() ? Plan.Tool.fromWire(text(call, "tool")) : null;
            if (tool == null || !Plan.ALLOWED.get(intent).contains(tool)) {
                return new Invalid(at + ".tool");
            }
            Object read = call(tool, call, at, message, references, scale);
            if (read instanceof Invalid invalid) {
                return invalid;
            }
            checked.add((Plan.Call) read);
        }
        if (checked.stream().noneMatch(call -> call.tool() == Plan.PRIMARY.get(intent))) {
            return new Invalid("calls");
        }
        return new Valid(new Plan(intent, List.copyOf(checked), null, null, notUnderstood));
    }

    /** A checked call, or {@link Invalid}. */
    private Object call(Plan.Tool tool, JsonNode call, String at, String message, References.Table references,
                        long scale) {
        return switch (tool) {
            case SEARCH_LISTINGS -> {
                JsonNode criteria = call.get("criteria");
                yield criteria != null && criteria.isObject()
                        ? new Plan.Search(criteria, enumOf(SearchSort.class, text(call, "sort")))
                        : new Invalid(at + ".criteria");
            }
            case GET_LISTING, SIMILAR_LISTINGS -> {
                Optional<UUID> id = reference(call, references);
                yield id.<Object>map(found -> new Plan.OneListing(tool, found)).orElse(new Invalid(at + ".ref"));
            }
            case COMPARE_LISTINGS -> compare(call, at, references);
            case SIMULATE_FINANCING -> finance(call, at, references, scale);
            case CRIT_AIR -> critAir(call, at, references);
            case ESTIMATE_VALUE -> value(call, at);
            case SEARCH_HELP -> help(call, at, message);
        };
    }

    private Object compare(JsonNode call, String at, References.Table references) {
        JsonNode refs = call.get("refs");
        if (refs == null || !refs.isArray() || refs.size() < CompareListingsTool.MIN_LISTINGS
                || refs.size() > CompareListingsTool.MAX_LISTINGS) {
            return new Invalid(at + ".refs");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode ref : refs) {
            Optional<UUID> id = ref.isTextual() ? references.resolve(ref.asText()) : Optional.empty();
            if (id.isEmpty() || !ids.add(id.get())) {
                return new Invalid(at + ".refs");
            }
        }
        return new Plan.Compare(List.copyOf(ids));
    }

    private Object finance(JsonNode call, String at, References.Table references, long scale) {
        Optional<UUID> listing = reference(call, references);
        Long amount = null;
        if (listing.isEmpty()) {
            if (call.hasNonNull("ref")) {
                return new Invalid(at + ".ref");
            }
            Long euros = integer(call, "amountEuros");
            if (euros == null || euros < MIN_AMOUNT_EUROS || euros > MAX_AMOUNT_EUROS) {
                return new Invalid(at + ".amountEuros");
            }
            amount = euros * scale;
        }
        Long down = integer(call, "downPaymentEuros");
        Long downPayment = down != null && down >= 0 && down <= MAX_AMOUNT_EUROS ? down * scale : null;
        Long months = integer(call, "months");
        Integer duration = months != null && months >= CreditFormula.MIN_MONTHS && months <= CreditFormula.MAX_MONTHS
                && months % 12 == 0 ? months.intValue() : null;
        Double rate = number(call, "ratePercent");
        Double ratePercent = rate != null && rate >= 0 && rate <= CreditFormula.MAX_ANNUAL_RATE_PERCENT ? rate : null;
        return new Plan.Finance(listing.orElse(null), amount, downPayment, duration, ratePercent);
    }

    private Object critAir(JsonNode call, String at, References.Table references) {
        Optional<UUID> listing = reference(call, references);
        if (listing.isPresent()) {
            return new Plan.CritAirOf(listing.get(), null, null, null);
        }
        if (call.hasNonNull("ref")) {
            return new Invalid(at + ".ref");
        }
        FuelType fuel = fuel(call);
        Long year = integer(call, "year");
        if (fuel == null) {
            return new Invalid(at + ".fuel");
        }
        if (year == null || year < MIN_YEAR || year > Year.now().getValue() + 1) {
            return new Invalid(at + ".year");
        }
        return new Plan.CritAirOf(null, fuel, year.intValue(), month(call));
    }

    private Object value(JsonNode call, String at) {
        String model = text(call, "model");
        if (model == null || model.length() > 60) {
            return new Invalid(at + ".model");
        }
        String make = text(call, "make");
        FuelType fuel = fuel(call);
        if (fuel == null) {
            return new Invalid(at + ".fuel");
        }
        Long power = integer(call, "powerHp");
        if (power == null || power < MIN_POWER_HP || power > MAX_POWER_HP) {
            return new Invalid(at + ".powerHp");
        }
        Long year = integer(call, "year");
        if (year == null || year < MIN_YEAR || year > Year.now().getValue() + 1) {
            return new Invalid(at + ".year");
        }
        Long mileage = integer(call, "mileageKm");
        if (mileage == null || mileage < 0 || mileage > MAX_MILEAGE_KM) {
            return new Invalid(at + ".mileageKm");
        }
        return new Plan.Value(make != null && make.length() <= 60 ? make : null, model, fuel, power.intValue(),
                year.intValue(), month(call), mileage.intValue(), enumOf(ValuationScope.class, text(call, "scope")));
    }

    /** A help query may only be made of the user's own words: the model cannot search for something else. */
    private Object help(JsonNode call, String at, String message) {
        String query = text(call, "query");
        if (query == null || query.length() > HelpIndex.MAX_QUERY) {
            return new Invalid(at + ".query");
        }
        Set<String> written = Set.copyOf(words(message));
        if (words(query).isEmpty() || !written.containsAll(words(query))) {
            return new Invalid(at + ".query");
        }
        return new Plan.Help(query);
    }

    private static Optional<UUID> reference(JsonNode call, References.Table references) {
        String ref = text(call, "ref");
        return ref == null ? Optional.empty() : references.resolve(ref);
    }

    private static boolean noCalls(JsonNode answer) {
        JsonNode calls = answer.get("calls");
        return calls == null || calls.isNull() || (calls.isArray() && calls.isEmpty());
    }

    /** The model's quotations of what became nothing: kept only when they are in the message. */
    private static List<String> quotations(JsonNode node, String message) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        String folded = fold(message);
        Set<String> kept = new LinkedHashSet<>();
        for (JsonNode quote : node) {
            if (quote.isTextual() && !quote.asText().isBlank() && folded.contains(fold(quote.asText().strip()))) {
                kept.add(quote.asText().strip());
            }
        }
        return kept.stream().limit(MAX_NOT_UNDERSTOOD).toList();
    }

    private static FuelType fuel(JsonNode call) {
        FuelType fuel = enumOf(FuelType.class, text(call, "fuel"));
        return fuel == FuelType.OTHER ? null : fuel;
    }

    private static Integer month(JsonNode call) {
        Long month = integer(call, "month");
        return month != null && month >= 1 && month <= 12 ? month.intValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    /** An integral number; "15000" or 15000.5 is absent. */
    private static Long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static List<String> words(String text) {
        return Arrays.stream(NOT_A_WORD.split(fold(text))).filter(word -> !word.isEmpty()).toList();
    }
}
