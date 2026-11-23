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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * <p>Three plans are read for what they mean rather than refused (docs/assistant-plan.md, 12, the
 * second run): a plan whose calls are another intent's own, with that intent's own tool missing
 * ({@code EXPLAIN} with a lone {@code crit_air}: the Crit'Air); an intent about one listing with no
 * call, which is its tool on the listing meant; and a valuation or a described car's Crit'Air with a
 * detail absent, which is the question {@code VEHICLE}: a second call cannot invent what the user
 * did not say.
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
    /** Asking for a comparison, in the folded message. */
    private static final Pattern COMPARING = Pattern.compile("compar|differen|\\bversus\\b|\\bvs\\b|laquelle (est|des)");

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
            if (!noCalls(answer)) {
                return new Invalid("calls");
            }
            // "Which listing?" to a comparison with fewer than two known has no good answer: the
            // user is told how many there are instead, and how to find another.
            if (question == Plan.Question.WHICH_LISTING && references.references().size() < CompareListingsTool.MIN_LISTINGS
                    && COMPARING.matcher(fold(message)).find()) {
                return new Valid(new Plan(Plan.Intent.COMPARE,
                        List.of(new Plan.TooFewToCompare(references.ids())), null, null, notUnderstood));
            }
            return new Valid(new Plan(intent, List.of(), null, question, notUnderstood));
        }

        long scale = Math.round(Math.pow(10, minorUnit));
        JsonNode calls = answer.get("calls");
        if (noCalls(answer) && ON_THE_LISTING_MEANT.contains(intent)) {
            // « Ça ferait combien par mois ? » planned as FINANCE with no call: the tool, on the
            // listing meant. With none meant, the planner's own question, if it asked one.
            calls = JSON.arrayNode().add(JSON.objectNode().put("tool", Plan.PRIMARY.get(intent).wireName()));
            if (call(Plan.PRIMARY.get(intent), calls.get(0), "calls[0]", message, references, scale) instanceof Invalid) {
                Plan.Question asked = enumOf(Plan.Question.class, text(answer, "clarify"));
                return asked != null ? new Valid(new Plan(Plan.Intent.CLARIFY, List.of(), null, asked, notUnderstood))
                        : new Invalid("calls");
            }
        }
        if (calls == null || !calls.isArray() || calls.isEmpty() || calls.size() > MAX_CALLS) {
            return new Invalid("calls");
        }
        Plan.Intent planned = intentOfCalls(intent, calls);
        List<Plan.Call> checked = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            String at = "calls[" + i + "]";
            JsonNode call = calls.get(i);
            Plan.Tool tool = call != null && call.isObject() ? Plan.Tool.fromWire(text(call, "tool")) : null;
            if (tool == null || !Plan.ALLOWED.get(planned).contains(tool)) {
                return new Invalid(at + ".tool");
            }
            Object read = call(tool, call, at, message, references, scale);
            if (read instanceof Invalid invalid) {
                return invalid;
            }
            if (read instanceof DetailsMissing) {
                return new Valid(new Plan(Plan.Intent.CLARIFY, List.of(), null, Plan.Question.VEHICLE, notUnderstood));
            }
            checked.add((Plan.Call) read);
        }
        if (checked.stream().noneMatch(call -> call.tool() == Plan.PRIMARY.get(planned))) {
            return new Invalid("calls");
        }
        return new Valid(new Plan(planned, List.copyOf(checked), null, null, notUnderstood));
    }

    /** The intents whose tool can run with no argument, on the listing meant. */
    private static final Set<Plan.Intent> ON_THE_LISTING_MEANT = EnumSet.of(Plan.Intent.EXPLAIN, Plan.Intent.COMPARE,
            Plan.Intent.SIMILAR, Plan.Intent.FINANCE, Plan.Intent.CRIT_AIR);
    private static final com.fasterxml.jackson.databind.node.JsonNodeFactory JSON =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;

    /** A detail of a described car the user has not given: asked, not guessed. */
    private record DetailsMissing() {
    }

    /**
     * The intent the calls are: when none is the intent's own tool, though the intent may add them,
     * and the first is another intent's own, which takes them all. {@code EXPLAIN} with a lone
     * {@code crit_air} is the Crit'Air.
     */
    private static Plan.Intent intentOfCalls(Plan.Intent intent, JsonNode calls) {
        List<Plan.Tool> tools = new ArrayList<>();
        for (JsonNode call : calls) {
            Plan.Tool tool = call != null && call.isObject() ? Plan.Tool.fromWire(text(call, "tool")) : null;
            if (tool == null || !Plan.ALLOWED.get(intent).contains(tool)) {
                return intent;
            }
            tools.add(tool);
        }
        if (tools.contains(Plan.PRIMARY.get(intent))) {
            return intent;
        }
        return Plan.PRIMARY.entrySet().stream()
                .filter(entry -> entry.getValue() == tools.getFirst() && Plan.ALLOWED.get(entry.getKey()).containsAll(tools))
                .map(java.util.Map.Entry::getKey)
                .findFirst().orElse(intent);
    }

    /** A checked call, {@link Invalid}, or {@link DetailsMissing}. */
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
                Optional<UUID> id = code(call.get("ref")) != null ? reference(call, references) : implicit(references);
                yield id.<Object>map(found -> new Plan.OneListing(tool, found)).orElse(new Invalid(at + ".ref"));
            }
            case COMPARE_LISTINGS -> compare(call, at, references);
            case SIMULATE_FINANCING -> finance(call, at, message, references, scale);
            case CRIT_AIR -> critAir(call, at, references);
            case ESTIMATE_VALUE -> value(call, at);
            case SEARCH_HELP -> help(call, at, message);
        };
    }

    /**
     * Two or three known listings. With fewer than two known there is nothing to compare, whatever
     * the codes say: {@link Plan.TooFewToCompare}. With no codes, and two or three known, those.
     */
    private Object compare(JsonNode call, String at, References.Table references) {
        if (references.references().size() < CompareListingsTool.MIN_LISTINGS) {
            return new Plan.TooFewToCompare(references.ids());
        }
        JsonNode refs = call.get("refs");
        if ((refs == null || refs.isNull() || (refs.isArray() && refs.isEmpty()))
                && references.references().size() <= CompareListingsTool.MAX_LISTINGS) {
            return new Plan.Compare(references.ids());
        }
        if (refs == null || !refs.isArray() || refs.size() < CompareListingsTool.MIN_LISTINGS
                || refs.size() > CompareListingsTool.MAX_LISTINGS) {
            return new Invalid(at + ".refs");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode ref : refs) {
            String code = code(ref);
            Optional<UUID> id = code == null ? Optional.empty() : references.resolve(code);
            if (id.isEmpty() || !ids.add(id.get())) {
                return new Invalid(at + ".refs");
            }
        }
        return new Plan.Compare(List.copyOf(ids));
    }

    private Object finance(JsonNode call, String at, String message, References.Table references, long scale) {
        Optional<UUID> listing = reference(call, references);
        Long amount = null;
        if (listing.isEmpty()) {
            if (code(call.get("ref")) != null) {
                return new Invalid(at + ".ref");
            }
            Long euros = integer(call, "amountEuros");
            Optional<UUID> copied = euros == null ? Optional.empty() : copiedFromLabel(euros, message, references);
            if (copied.isPresent()) {
                // A listing's own price, which the user never wrote: the model copied it from the
                // label. That listing is what was asked about, and the tool reads its price.
                listing = copied;
                euros = null;
            }
            if (euros == null && !call.hasNonNull("amountEuros")) {
                // No price and no code: the listing meant is the page's, or the only one known.
                // A price written wrong is not that: it stays an error.
                listing = implicit(references);
                if (listing.isEmpty()) {
                    return new Invalid(at + ".amountEuros");
                }
            } else if (listing.isPresent()) {
                // The listing above: its price is the tool's to read.
            } else if (euros == null || euros < MIN_AMOUNT_EUROS || euros > MAX_AMOUNT_EUROS) {
                return new Invalid(at + ".amountEuros");
            } else {
                amount = euros * scale;
            }
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
        if (code(call.get("ref")) != null) {
            return new Invalid(at + ".ref");
        }
        FuelType fuel = fuel(call);
        Long year = integer(call, "year");
        if (fuel == null && year == null) {
            Optional<UUID> meant = implicit(references);
            if (meant.isPresent()) {
                return new Plan.CritAirOf(meant.get(), null, null, null);
            }
        }
        if (absent(call, "fuel") || absent(call, "year")) {
            return new DetailsMissing();
        }
        if (fuel == null) {
            return new Invalid(at + ".fuel");
        }
        if (year == null || year < MIN_YEAR || year > Year.now().getValue() + 1) {
            return new Invalid(at + ".year");
        }
        return new Plan.CritAirOf(null, fuel, year.intValue(), month(call));
    }

    private Object value(JsonNode call, String at) {
        if (Stream.of("model", "fuel", "powerHp", "year", "mileageKm").anyMatch(field -> absent(call, field))) {
            return new DetailsMissing();
        }
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
        List<String> written = words(message);
        if (words(query).isEmpty()
                || !words(query).stream().allMatch(word -> written.stream().anyMatch(own -> formOf(word, own)))) {
            return new Invalid(at + ".query");
        }
        return new Plan.Help(query);
    }

    /**
     * The same word, or another form of it: « arrêter » for « arrête », « disparue » for « disparu »,
     * « mails » for « mail ». Both of four letters or more, differing only in their last two.
     */
    static boolean formOf(String word, String written) {
        if (word.equals(written)) {
            return true;
        }
        int common = 0;
        while (common < Math.min(word.length(), written.length()) && word.charAt(common) == written.charAt(common)) {
            common++;
        }
        return common >= 4 && common >= Math.max(word.length(), written.length()) - 2;
    }

    /**
     * The listing meant when none is named (docs/assistant-plan.md, 12, A2 fixes): the page's, or the
     * only one the user has seen. "Combien par mois ?" after one result is about that one.
     */
    private static Optional<UUID> implicit(References.Table references) {
        if (references.pageListing() != null) {
            return references.resolve(references.pageListing());
        }
        return references.references().size() == 1
                ? Optional.of(references.references().getFirst().listingId()) : Optional.empty();
    }

    /**
     * A listing code as the model may write it: "L2", "l2", "L 2". Blank or null is no code at all,
     * which lets the listing meant apply. Anything else, a number or an id among them, is kept
     * as written and fails to resolve.
     */
    static String code(JsonNode ref) {
        if (ref == null || ref.isNull()) {
            return null;
        }
        if (!ref.isTextual()) {
            return "?";
        }
        String code = ref.asText().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return code.isEmpty() ? null : code;
    }

    /** A number as a user writes it: « 24990 », « 24 990 », « 24.990 », « 25k ». */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[ .\\u00a0\\u202f]\\d{3})*(?:[,.]\\d+)?\\s*(k\\b)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * The one listing whose label carries this amount as its price, when the message does not hold
     * that amount: the model copied it from the label, and the user asked about that listing
     * (« la première sur 5 ans »). A message that holds it is the user's amount.
     */
    private static Optional<UUID> copiedFromLabel(long euros, String message, References.Table references) {
        if (written(message).contains(euros)) {
            return Optional.empty();
        }
        Pattern price = Pattern.compile("(^|\\D)" + euros + " EUR");
        List<UUID> carrying = references.references().stream()
                .filter(reference -> price.matcher(reference.label()).find())
                .map(References.Reference::listingId)
                .distinct()
                .toList();
        return carrying.size() == 1 ? Optional.of(carrying.getFirst()) : Optional.empty();
    }

    /** The whole-euro amounts written in the message. */
    private static Set<Long> written(String message) {
        Set<Long> amounts = new java.util.HashSet<>();
        var numbers = NUMBER.matcher(message);
        while (numbers.find()) {
            String digits = numbers.group().replaceAll("(?i)[\\sk\\u00a0\\u202f]", "").replaceAll("[,.]\\d{1,2}$", "")
                    .replaceAll("[.,]", "");
            try {
                long value = Long.parseLong(digits);
                amounts.add(numbers.group(1) != null ? value * 1000 : value);
            } catch (NumberFormatException e) {
                // Too long to be an amount.
            }
        }
        return amounts;
    }

    private static Optional<UUID> reference(JsonNode call, References.Table references) {
        String ref = code(call.get("ref"));
        return ref == null ? Optional.empty() : references.resolve(ref);
    }

    /** Not there, or null: not given, which is not the same as given wrong. */
    private static boolean absent(JsonNode call, String field) {
        JsonNode value = call.get(field);
        return value == null || value.isNull() || (value.isTextual() && value.asText().isBlank());
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
