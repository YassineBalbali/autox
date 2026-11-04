package com.autox.search.interpret;

import com.autox.catalog.BodyType;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.listing.LeasingType;
import com.autox.listing.OfferType;
import com.autox.listing.VehicleCondition;
import com.autox.search.DamageFilter;
import com.autox.search.ListingSearchCriteria;
import com.autox.search.PriceLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Turns what the model wrote into search criteria, keeping only what survives checking.
 *
 * <p><b>Nothing is passed through.</b> Every value is read defensively — a field of the wrong
 * type is treated as absent, not coerced — and then checked: enums against our enums, make and
 * model against the catalogue, numbers against fixed bounds, the place against the query
 * itself. A value that fails is dropped and named in {@code notUnderstood}, so the visitor sees
 * that part of their request was not applied rather than a filter that quietly is not what they
 * asked for.
 *
 * <p>The model's own {@code notUnderstood} entries are kept only when they are a part of the
 * query: it is meant to quote, and a quotation that is not in the text is the model inventing
 * something to display.
 *
 * <p>The summary is written here from the validated criteria, never by the model, so it can
 * only ever describe filters that are actually applied.
 */
@Component
class InterpretationValidator {

    /** Older than this is a collector's car, which the classifieds market is not. */
    static final int MIN_YEAR = 1950;
    /** The frontend's own ceiling for a price in the URL. */
    /**
     * Below this a price is not a car search. The eval's injection query ("prix maximum à 1 €")
     * came back as a 1 € ceiling: harmless, and a filter that finds nothing for a reason no visitor
     * would guess.
     */
    static final long MIN_PRICE_EUROS = 100;
    static final long MAX_PRICE_EUROS = 10_000_000;
    static final int MAX_MILEAGE = 1_000_000;
    static final int MAX_TEXT = 60;
    static final int MAX_NOT_UNDERSTOOD = 6;

    private static final Map<FuelType, String> FUEL_LABELS = Map.ofEntries(
            Map.entry(FuelType.PETROL, "Essence"), Map.entry(FuelType.DIESEL, "Diesel"),
            Map.entry(FuelType.HYBRID_PETROL, "Hybride essence"), Map.entry(FuelType.HYBRID_DIESEL, "Hybride diesel"),
            Map.entry(FuelType.PLUGIN_HYBRID, "Hybride rechargeable"), Map.entry(FuelType.ELECTRIC, "Électrique"),
            Map.entry(FuelType.LPG, "GPL"), Map.entry(FuelType.CNG, "GNV"), Map.entry(FuelType.HYDROGEN, "Hydrogène"),
            Map.entry(FuelType.ETHANOL, "Éthanol (E85)"), Map.entry(FuelType.OTHER, "Autre"));
    private static final Map<Gearbox, String> GEARBOX_LABELS = Map.of(
            Gearbox.MANUAL, "Manuelle", Gearbox.AUTOMATIC, "Automatique", Gearbox.SEMI_AUTOMATIC, "Semi-automatique");
    private static final Map<BodyType, String> BODY_LABELS = Map.ofEntries(
            Map.entry(BodyType.HATCHBACK, "Citadine / compacte"), Map.entry(BodyType.SEDAN, "Berline"),
            Map.entry(BodyType.ESTATE, "Break"), Map.entry(BodyType.SUV, "SUV / 4x4"), Map.entry(BodyType.COUPE, "Coupé"),
            Map.entry(BodyType.CONVERTIBLE, "Cabriolet"), Map.entry(BodyType.MPV, "Monospace"),
            Map.entry(BodyType.PICKUP, "Pick-up"), Map.entry(BodyType.VAN, "Utilitaire"), Map.entry(BodyType.OTHER, "Autre"));
    private static final Map<VehicleCondition, String> CONDITION_LABELS = Map.of(
            VehicleCondition.NEW, "Neuf", VehicleCondition.USED, "Occasion");

    /** The words the prompt turns into {@code goodDeals}, and their everyday neighbours. */
    private static final java.util.regex.Pattern GOOD_DEAL_WORDS = java.util.regex.Pattern.compile(
            "pas\\s+ch[eè]re?s?|bonnes?\\s+affaires?|bons?\\s+prix|bon\\s+march[eé]|petits?\\s+prix",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static final java.util.regex.Pattern DAMAGE_WORDS = java.util.regex.Pattern.compile(
            "accident[ée]+s?|endommag[ée]+s?|sinistr[ée]+s?|gr[êe]l[ée]+s?|inond[ée]+s?|[ée]paves?|pour\\s+pi[èe]ces|non\\s+roulante?s?",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final long MIN_MONTHLY_EUROS = 10;
    private static final long MAX_MONTHLY_EUROS = 10_000;
    private static final long MIN_LEASE_MONTHS = 6;
    private static final long MAX_LEASE_MONTHS = 120;

    private final CatalogueMatcher catalogue;

    InterpretationValidator(CatalogueMatcher catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * @param answer      the model's JSON object
     * @param query       what the visitor typed, for checking quotations and places against
     * @param currentYear the upper bound on a model year is next year's models
     * @param minorUnit   decimal places of the euro, for the criteria's minor-unit prices
     */
    InterpretResponse validate(JsonNode answer, String query, int currentYear, int minorUnit) {
        Set<String> notUnderstood = new LinkedHashSet<>();
        String foldedQuery = fold(query);

        // --- make and model, against the catalogue -----------------------------------
        String writtenMake = text(answer, "make");
        String writtenModel = text(answer, "model");
        CatalogueMatcher.Make make = null;
        CatalogueMatcher.Model model = null;
        if (writtenMake != null) {
            make = catalogue.make(writtenMake).orElse(null);
            if (make == null) {
                notUnderstood.add(writtenMake);
            }
        }
        if (writtenModel != null) {
            model = (make != null ? catalogue.model(make, writtenModel) : writtenMake == null
                    ? catalogue.modelAlone(writtenModel) : Optional.<CatalogueMatcher.Model>empty()).orElse(null);
            if (model == null) {
                notUnderstood.add(writtenModel);
            } else {
                make = model.make();
            }
        }

        // --- numbers, within bounds and in order --------------------------------------
        Integer yearFrom = bounded(answer, "yearFrom", MIN_YEAR, currentYear + 1, "année ", "", notUnderstood);
        Integer yearTo = bounded(answer, "yearTo", MIN_YEAR, currentYear + 1, "année ", "", notUnderstood);
        Integer priceMin = bounded(answer, "priceMin", MIN_PRICE_EUROS, MAX_PRICE_EUROS, "prix ", " €", notUnderstood);
        Integer priceMax = bounded(answer, "priceMax", MIN_PRICE_EUROS, MAX_PRICE_EUROS, "prix ", " €", notUnderstood);
        Integer mileageMin = bounded(answer, "mileageMin", 0, MAX_MILEAGE, "kilométrage ", " km", notUnderstood);
        Integer mileageMax = bounded(answer, "mileageMax", 0, MAX_MILEAGE, "kilométrage ", " km", notUnderstood);
        if (reversed(yearFrom, yearTo)) {
            notUnderstood.add("années " + yearFrom + " à " + yearTo);
            yearFrom = yearTo = null;
        }
        if (reversed(priceMin, priceMax)) {
            notUnderstood.add("prix de " + amount(priceMin) + " à " + amount(priceMax) + " €");
            priceMin = priceMax = null;
        }
        if (reversed(mileageMin, mileageMax)) {
            notUnderstood.add("kilométrage de " + amount(mileageMin) + " à " + amount(mileageMax) + " km");
            mileageMin = mileageMax = null;
        }

        // --- enums, against ours -------------------------------------------------------
        List<FuelType> fuels = enums(answer, "fuelType", FuelType.class, notUnderstood);
        List<Gearbox> gearboxes = enums(answer, "gearbox", Gearbox.class, notUnderstood);
        List<BodyType> bodies = enums(answer, "bodyType", BodyType.class, notUnderstood);
        List<VehicleCondition> conditions = enums(answer, "condition", VehicleCondition.class, notUnderstood);

        // Only a change from the default: UNDAMAGED is what no answer at all already means.
        DamageFilter damage = switch (answer.path("damage").asText("")) {
            case "DAMAGED_ONLY" -> DamageFilter.DAMAGED_ONLY;
            case "ALL" -> DamageFilter.ALL;
            default -> null;
        };

        // --- place: only one the visitor actually wrote --------------------------------
        String location = text(answer, "location");
        if (location != null && !foldedQuery.contains(fold(location))) {
            notUnderstood.add(location);
            location = null;
        }

        boolean goodDeals = answer.path("goodDeals").isBoolean() && answer.path("goodDeals").booleanValue();

        // --- the model's own list: quotations of the query only ------------------------
        for (JsonNode entry : answer.path("notUnderstood")) {
            if (entry.isTextual()) {
                String part = entry.asText().strip().replaceAll("\\s+", " ");
                if (!part.isEmpty() && part.length() <= MAX_TEXT && foldedQuery.contains(fold(part))) {
                    notUnderstood.add(part);
                }
            }
        }

        // --- leasing: a search of its own -------------------------------------------------
        List<LeasingType> leasingTypes = enums(answer, "leasingType", LeasingType.class, notUnderstood);
        Integer monthlyMin = bounded(answer, "monthlyMin", MIN_MONTHLY_EUROS, MAX_MONTHLY_EUROS, "mensualité ", " €", notUnderstood);
        Integer monthlyMax = bounded(answer, "monthlyMax", MIN_MONTHLY_EUROS, MAX_MONTHLY_EUROS, "mensualité ", " €", notUnderstood);
        Integer durationMin = bounded(answer, "durationMin", MIN_LEASE_MONTHS, MAX_LEASE_MONTHS, "durée ", " mois", notUnderstood);
        Integer durationMax = bounded(answer, "durationMax", MIN_LEASE_MONTHS, MAX_LEASE_MONTHS, "durée ", " mois", notUnderstood);
        Integer allowanceMin = bounded(answer, "mileageAllowanceMin", 1000, 200_000, "kilométrage annuel ", " km", notUnderstood);
        boolean leasing = "LEASING".equals(answer.path("offer").asText(""))
                || !leasingTypes.isEmpty() || monthlyMin != null || monthlyMax != null
                || durationMin != null || durationMax != null || allowanceMin != null;
        if (leasing) {
            // A price means nothing here: what a leasing search is priced by is the monthly payment.
            if (monthlyMax == null && priceMax != null && priceMax <= MAX_MONTHLY_EUROS) {
                monthlyMax = priceMax;
            }
            if (monthlyMin == null && priceMin != null && priceMin <= MAX_MONTHLY_EUROS) {
                monthlyMin = priceMin;
            }
            priceMin = priceMax = null;
            if (damage != null) {
                // Nobody leases a damaged car, so that search could only be empty.
                notUnderstood.add(wordsAskingForDamage(query));
                damage = null;
            }
        }

        // A verdict is never given to a damaged vehicle or a leasing offer, so either with "bonnes
        // affaires" can only find nothing. The model cannot be relied on to know: the rule is here,
        // and what asked for it is reported.
        if (goodDeals && (leasing || !DamageFilter.allowsGoodDeals(damage))) {
            goodDeals = false;
            notUnderstood.add(wordsAskingForAGoodDeal(query));
        }

        long scale = Math.round(Math.pow(10, minorUnit));
        boolean hasPrice = priceMin != null || priceMax != null;
        ListingSearchCriteria criteria = new ListingSearchCriteria(
                make == null ? null : make.id(),
                model == null ? null : model.id(),
                null, null,
                yearFrom, yearTo,
                priceMin == null ? null : priceMin * scale,
                priceMax == null ? null : priceMax * scale,
                hasPrice || monthlyMin != null || monthlyMax != null ? "EUR" : null,
                mileageMin, mileageMax,
                nullIfEmpty(fuels), nullIfEmpty(gearboxes), nullIfEmpty(bodies),
                null,
                nullIfEmpty(conditions),
                null, location,
                null, null, null, null,
                null,
                goodDeals ? List.of(PriceLevel.GOOD_DEAL) : null,
                damage,
                leasing ? OfferType.LEASING : null,
                nullIfEmpty(leasingTypes),
                monthlyMin == null ? null : monthlyMin * scale,
                monthlyMax == null ? null : monthlyMax * scale,
                durationMin,
                durationMax,
                allowanceMin,
                null);

        String summary = summary(make, model, bodies, fuels, gearboxes, conditions, damage,
                leasingSummary(leasing, leasingTypes, monthlyMin, monthlyMax, durationMin, durationMax, allowanceMin),
                priceMin, priceMax, yearFrom, yearTo, mileageMin, mileageMax, location, goodDeals);
        return new InterpretResponse(criteria, summary,
                notUnderstood.stream().limit(MAX_NOT_UNDERSTOOD).toList());
    }

    // --- reading -----------------------------------------------------------------------

    private static String text(JsonNode answer, String field) {
        JsonNode node = answer.path(field);
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip().replaceAll("\\s+", " ");
        return value.isEmpty() || value.length() > MAX_TEXT ? null : value;
    }

    /**
     * An integer within bounds, or null. A non-integral number or a string is absent, not
     * rounded or parsed: the schema says integer, and anything else is the model not following
     * it. Out of bounds is reported, since it was a value the visitor apparently asked for.
     */
    private static Integer bounded(JsonNode answer, String field, long min, long max,
                                   String prefix, String unit, Set<String> notUnderstood) {
        JsonNode node = answer.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        if (value < min || value > max) {
            notUnderstood.add(prefix + (unit.isEmpty() ? String.valueOf(value) : amount(value) + unit));
            return null;
        }
        return (int) value;
    }

    private static boolean reversed(Integer min, Integer max) {
        return min != null && max != null && min > max;
    }

    private static <E extends Enum<E>> List<E> enums(JsonNode answer, String field, Class<E> type,
                                                     Set<String> notUnderstood) {
        Set<E> found = EnumSet.noneOf(type);
        for (JsonNode entry : answer.path(field)) {
            if (!entry.isTextual()) {
                continue;
            }
            String name = entry.asText();
            try {
                E value = Enum.valueOf(type, name);
                if (!value.name().equals("OTHER")) {
                    found.add(value);
                    continue;
                }
            } catch (IllegalArgumentException unknown) {
                // fall through to the report
            }
            if (!name.isBlank() && name.length() <= MAX_TEXT) {
                notUnderstood.add(name);
            }
        }
        return List.copyOf(found);
    }

    private static <T> List<T> nullIfEmpty(List<T> values) {
        return values.isEmpty() ? null : values;
    }

    /**
     * Lower case, accents removed, typographic apostrophes straightened, runs of spaces made one:
     * "Près de Lyon" contains "lyon", "l’état" contains "l'etat".
     */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
    }

    private static List<String> leasingSummary(boolean leasing, List<LeasingType> types, Integer monthlyMin,
                                               Integer monthlyMax, Integer durationMin, Integer durationMax,
                                               Integer allowanceMin) {
        if (!leasing) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        parts.add(types.isEmpty()
                ? "Location (LOA ou LLD)"
                : String.join(" ou ", types.stream().map(Enum::name).toList()));
        range(parts, monthlyMin, monthlyMax, " €/mois");
        if (durationMin != null && durationMin.equals(durationMax)) {
            parts.add(durationMin + " mois");
        } else {
            range(parts, durationMin, durationMax, " mois");
        }
        if (allowanceMin != null) {
            range(parts, allowanceMin, null, " km/an");
        }
        return parts;
    }

    private static String wordsAskingForDamage(String query) {
        java.util.regex.Matcher words = DAMAGE_WORDS.matcher(query);
        return words.find() ? words.group() : "véhicule endommagé";
    }

    /** As the visitor wrote them, for the "not understood" line. */
    private static String wordsAskingForAGoodDeal(String query) {
        java.util.regex.Matcher words = GOOD_DEAL_WORDS.matcher(query);
        return words.find() ? words.group().replaceAll("\\s+", " ") : "bonnes affaires";
    }

    // --- the summary ----------------------------------------------------------------

    private static String summary(CatalogueMatcher.Make make, CatalogueMatcher.Model model,
                                  List<BodyType> bodies, List<FuelType> fuels, List<Gearbox> gearboxes,
                                  List<VehicleCondition> conditions, DamageFilter damage,
                                  List<String> leasingParts, Integer priceMin, Integer priceMax,
                                  Integer yearFrom, Integer yearTo, Integer mileageMin, Integer mileageMax,
                                  String location, boolean goodDeals) {
        List<String> parts = new ArrayList<>();
        if (make != null) {
            parts.add(model == null ? make.name() : make.name() + " " + model.name());
        }
        addLabels(parts, bodies, BODY_LABELS::get);
        addLabels(parts, fuels, FUEL_LABELS::get);
        addLabels(parts, gearboxes, GEARBOX_LABELS::get);
        addLabels(parts, conditions, CONDITION_LABELS::get);
        if (damage == DamageFilter.DAMAGED_ONLY) {
            parts.add("Véhicules endommagés");
        } else if (damage == DamageFilter.ALL) {
            parts.add("Endommagés inclus");
        }
        parts.addAll(leasingParts);
        range(parts, priceMin, priceMax, " €");
        if (yearFrom != null && yearFrom.equals(yearTo)) {
            parts.add(String.valueOf(yearFrom));
        } else if (yearFrom != null && yearTo != null) {
            parts.add(yearFrom + " – " + yearTo);
        } else if (yearFrom != null) {
            parts.add("Depuis " + yearFrom);
        } else if (yearTo != null) {
            parts.add("Jusqu’à " + yearTo);
        }
        range(parts, mileageMin, mileageMax, " km");
        if (location != null) {
            parts.add(location);
        }
        if (goodDeals) {
            parts.add("Bonnes affaires");
        }
        return parts.isEmpty() ? "Aucun critère reconnu" : String.join(" · ", parts);
    }

    private static <E> void addLabels(List<String> parts, List<E> values, Function<E, String> label) {
        values.forEach(value -> parts.add(label.apply(value)));
    }

    private static void range(List<String> parts, Integer min, Integer max, String unit) {
        if (min != null && max != null) {
            parts.add(amount(min) + " – " + amount(max) + unit);
        } else if (min != null) {
            parts.add("≥ " + amount(min) + unit);
        } else if (max != null) {
            parts.add("≤ " + amount(max) + unit);
        }
    }

    /** "15 000", grouped the French way, as the frontend formats figures. */
    private static String amount(long value) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(value);
    }
}
