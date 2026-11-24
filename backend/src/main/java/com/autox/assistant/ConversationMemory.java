package com.autox.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * What a conversation remembers between turns, besides its messages and listings
 * (docs/assistant-plan.md, 12, the third run): the last search's criteria and the car last
 * described. The client holds them in {@link TurnRequest.State}, as it holds the rest.
 *
 * <p>Both were lost before: the model saw the earlier messages only, and had to write the earlier
 * criteria again from them, which it did in one run and not in the next (« moins de 15 000 € »
 * after « Une Golf » searched every car under 15 000 €). Now the model is shown them, and the
 * server takes from them what a plan leaves out ({@link PlanValidator}).
 *
 * <p>Read from the client like any input: only the smart search's criteria fields are kept, as
 * short strings, numbers, booleans or short lists of them; anything else is dropped, not refused.
 * The criteria are checked by the smart search's validator when the search runs, as any others.
 */
public final class ConversationMemory {

    /** The criteria fields the prompt lists ({@link AssistantPrompt#system}). */
    public static final List<String> CRITERIA = List.of("make", "model", "yearFrom", "yearTo", "priceMin", "priceMax",
            "mileageMin", "mileageMax", "fuelType", "gearbox", "bodyType", "condition", "offer", "leasingType",
            "monthlyMin", "monthlyMax", "durationMin", "durationMax", "mileageAllowanceMin", "damage", "location",
            "goodDeals");
    static final int MAX_TEXT = 60;
    static final int MAX_LIST = 10;

    /** What is remembered; either may be null. */
    public record Memory(ObjectNode search, TurnRequest.Car car) {
        public static final Memory NONE = new Memory(null, null);
    }

    private ConversationMemory() {
    }

    public static Memory of(TurnRequest.State state) {
        return new Memory(criteria(state.search()), state.car());
    }

    /**
     * The criteria worth remembering: the known fields with a value, in the prompt's order. Null,
     * false, blank and empty are no value; so is anything too long or of another shape. Null when
     * nothing is left: a search for everything is not one to build on.
     */
    public static ObjectNode criteria(JsonNode criteria) {
        if (criteria == null || !criteria.isObject()) {
            return null;
        }
        ObjectNode kept = JsonNodeFactory.instance.objectNode();
        for (String field : CRITERIA) {
            JsonNode value = criteria.get(field);
            if (scalar(value)) {
                kept.set(field, value);
            } else if (value != null && value.isArray() && !value.isEmpty() && value.size() <= MAX_LIST) {
                boolean allScalar = true;
                for (JsonNode item : value) {
                    allScalar &= scalar(item) && !item.isBoolean();
                }
                if (allScalar) {
                    kept.set(field, value);
                }
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    private static boolean scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.asText().isBlank() && value.asText().length() <= MAX_TEXT;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.isNumber() && Double.isFinite(value.asDouble());
    }

    /** What the next turn remembers: this plan's search and car, else what was remembered. */
    public static Memory after(Plan plan, Memory memory) {
        ObjectNode search = memory.search();
        TurnRequest.Car car = memory.car();
        for (Plan.Call call : plan.calls()) {
            switch (call) {
                case Plan.Search s -> search = criteria(s.criteria());
                case Plan.Value v -> car = new TurnRequest.Car(v.make(), v.model(), v.fuel(), v.powerHp(), v.year(),
                        v.month(), v.mileageKm());
                case Plan.CritAirOf c when c.listingId() == null -> car = car == null
                        ? new TurnRequest.Car(null, null, c.fuel(), null, c.year(), c.month(), null)
                        : new TurnRequest.Car(car.make(), car.model(), c.fuel(), car.powerHp(), c.year(), c.month(),
                                car.mileageKm());
                default -> {
                }
            }
        }
        return new Memory(search, car);
    }
}
