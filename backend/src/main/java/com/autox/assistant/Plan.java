package com.autox.assistant;

import com.autox.catalog.FuelType;
import com.autox.search.SearchSort;
import com.autox.search.ValuationScope;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A plan after {@link PlanValidator}: every value checked, every listing an id the server holds,
 * every amount in minor units. Nothing in it was passed through from the model unread.
 */
public record Plan(Intent intent, List<Call> calls, Reason noAnswer, Question clarify, List<String> notUnderstood) {

    /** What the user wants this turn. */
    public enum Intent {
        SEARCH, EXPLAIN, COMPARE, SIMILAR, FINANCE, CRIT_AIR, VALUE, HELP, NO_ANSWER, CLARIFY
    }

    /** The tools, by the names the planner writes. */
    public enum Tool {
        SEARCH_LISTINGS("search_listings"),
        GET_LISTING("get_listing"),
        COMPARE_LISTINGS("compare_listings"),
        SIMILAR_LISTINGS("similar_listings"),
        SIMULATE_FINANCING("simulate_financing"),
        CRIT_AIR("crit_air"),
        ESTIMATE_VALUE("estimate_value"),
        SEARCH_HELP("search_help");

        private final String wireName;

        Tool(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Tool fromWire(String name) {
            for (Tool tool : values()) {
                if (tool.wireName.equals(name)) {
                    return tool;
                }
            }
            return null;
        }
    }

    /** For each intent, the tool it needs first, then the others it may add. */
    static final Map<Intent, Tool> PRIMARY = Map.of(
            Intent.SEARCH, Tool.SEARCH_LISTINGS,
            Intent.EXPLAIN, Tool.GET_LISTING,
            Intent.COMPARE, Tool.COMPARE_LISTINGS,
            Intent.SIMILAR, Tool.SIMILAR_LISTINGS,
            Intent.FINANCE, Tool.SIMULATE_FINANCING,
            Intent.CRIT_AIR, Tool.CRIT_AIR,
            Intent.VALUE, Tool.ESTIMATE_VALUE,
            Intent.HELP, Tool.SEARCH_HELP);

    static final Map<Intent, Set<Tool>> ALLOWED = Map.of(
            Intent.SEARCH, EnumSet.of(Tool.SEARCH_LISTINGS),
            Intent.EXPLAIN, EnumSet.of(Tool.GET_LISTING, Tool.CRIT_AIR, Tool.SIMULATE_FINANCING, Tool.SIMILAR_LISTINGS),
            Intent.COMPARE, EnumSet.of(Tool.COMPARE_LISTINGS),
            Intent.SIMILAR, EnumSet.of(Tool.SIMILAR_LISTINGS),
            Intent.FINANCE, EnumSet.of(Tool.SIMULATE_FINANCING),
            Intent.CRIT_AIR, EnumSet.of(Tool.CRIT_AIR),
            Intent.VALUE, EnumSet.of(Tool.ESTIMATE_VALUE, Tool.CRIT_AIR),
            Intent.HELP, EnumSet.of(Tool.SEARCH_HELP));

    /**
     * Why a turn has no answer, shown as fixed text with a way forward (1.6). The planner may give
     * the first four; the others are the server's, from the tools or the turn itself.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantNoAnswerReason", enumAsRef = true)
    public enum Reason {
        OUT_OF_SCOPE, ADVICE, ONLY_IN_DESCRIPTION, UNCLEAR,
        NO_RESULTS, NOT_IN_HELP, NOT_COMPUTABLE, NOT_FINANCEABLE, NOTHING_TO_FINANCE, LISTING_UNAVAILABLE,
        /** A comparison with fewer than two listings known: the one shown, and a way to find another. */
        NOT_ENOUGH_LISTINGS,
        /** Two invalid plans in a row: « Je n'ai pas compris, pouvez-vous reformuler ? » */
        REPHRASE,
        /** The conversation has had its twelve turns. */
        CONVERSATION_OVER
    }

    static final Set<Reason> PLANNER_REASONS =
            EnumSet.of(Reason.OUT_OF_SCOPE, Reason.ADVICE, Reason.ONLY_IN_DESCRIPTION, Reason.UNCLEAR);

    /** A clarifying question, from a fixed list: the planner chooses one, the labels word it. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantQuestion", enumAsRef = true)
    public enum Question {
        BUDGET, OFFER_TYPE, WHICH_LISTING, VEHICLE
    }

    /** One checked tool call. */
    public sealed interface Call {
        Tool tool();
    }

    /** @param criteria the smart search's criteria object, checked by its own validator when the tool runs */
    public record Search(JsonNode criteria, SearchSort sort) implements Call {
        public Tool tool() {
            return Tool.SEARCH_LISTINGS;
        }
    }

    /** {@code get_listing} or {@code similar_listings}: one listing. */
    public record OneListing(Tool tool, UUID listingId) implements Call {
    }

    public record Compare(List<UUID> listingIds) implements Call {
        public Tool tool() {
            return Tool.COMPARE_LISTINGS;
        }
    }

    /** A comparison asked for with fewer than two listings known: those there are, none or one. */
    public record TooFewToCompare(List<UUID> listingIds) implements Call {
        public Tool tool() {
            return Tool.COMPARE_LISTINGS;
        }
    }

    /**
     * Amounts in minor units. Exactly one of {@code listingId} and {@code amount}. A value that failed
     * its check is null, and the tool's default applies: no down payment, 48 months, the example rate.
     */
    public record Finance(UUID listingId, Long amount, Long downPayment, Integer months, Double ratePercent)
            implements Call {
        public Tool tool() {
            return Tool.SIMULATE_FINANCING;
        }
    }

    /** A listing's class, or a described car's: {@code listingId}, or the fuel and year. */
    public record CritAirOf(UUID listingId, FuelType fuel, Integer year, Integer month) implements Call {
        public Tool tool() {
            return Tool.CRIT_AIR;
        }
    }

    public record Value(String make, String model, FuelType fuel, int powerHp, int year, Integer month,
                        int mileageKm, ValuationScope scope) implements Call {
        public Tool tool() {
            return Tool.ESTIMATE_VALUE;
        }
    }

    public record Help(String query) implements Call {
        public Tool tool() {
            return Tool.SEARCH_HELP;
        }
    }
}
