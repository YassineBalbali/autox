package com.autox.assistant;

import com.autox.assistant.tools.CompareListingsTool;
import com.autox.assistant.tools.CritAirTool;
import com.autox.assistant.tools.FinancingTool;
import com.autox.assistant.tools.HelpIndex;
import com.autox.assistant.tools.ListingFacts;
import com.autox.search.ListingSearchCriteria;
import com.autox.search.SearchSort;
import com.autox.search.dto.Valuation;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One part of an answer. The server builds every block from its own tools; the clients render it
 * with the French of the shared labels. There is no text written by a model in any block
 * (docs/assistant-plan.md, D1), only codes, figures and the site's own help text.
 *
 * <p>One shape with a {@code type}, the other fields present according to it, rather than one schema
 * per type: two generated clients read it, and a flat record is what both generators handle alike.
 *
 * @param listings      {@code LISTINGS}, {@code COMPARISON}, {@code LISTING_FACTS}: the cards
 * @param facts         {@code LISTING_FACTS}: what the answer says about the one listing
 * @param differences   {@code COMPARISON}
 * @param search        {@code SEARCH_LINK}: the filters to open on the ordinary results page
 * @param financing     {@code FINANCING}, with its mandatory mentions as codes
 * @param critAir       {@code CRIT_AIR}
 * @param listingId     {@code CRIT_AIR}, {@code FINANCING}, {@code NO_ANSWER}: the listing it is about
 * @param valuation     {@code VALUATION}: La Cote's answer, with or without an estimate
 * @param help          {@code HELP_PASSAGE}: the sections of the help pages that answer
 * @param notUnderstood {@code NOT_UNDERSTOOD}: the user's words that became nothing
 * @param reason        {@code NO_ANSWER}
 * @param question      {@code CLARIFY}
 */
@Schema(name = "AssistantBlock")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantBlock(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Type type,
        List<ListingCard> listings,
        ListingFacts facts,
        CompareListingsTool.Differences differences,
        SearchLink search,
        FinancingTool.Simulation financing,
        CritAirTool.Estimate critAir,
        UUID listingId,
        Valuation valuation,
        List<HelpIndex.Passage> help,
        List<String> notUnderstood,
        Plan.Reason reason,
        Plan.Question question) {

    @Schema(name = "AssistantBlockType", enumAsRef = true)
    public enum Type {
        LISTINGS, LISTING_FACTS, COMPARISON, SEARCH_LINK, FINANCING, CRIT_AIR, VALUATION, HELP_PASSAGE,
        NOT_UNDERSTOOD, NO_ANSWER, CLARIFY,
        /** The message held an e-mail address or a phone number, removed before it was sent anywhere. */
        CONTACT_REMOVED
    }

    /**
     * The validated filters, for "Voir les N résultats" on the ordinary results page.
     *
     * @param count  exact up to the search's cap; {@code capped} past it
     */
    @Schema(name = "AssistantSearchLink")
    public record SearchLink(ListingSearchCriteria criteria, SearchSort sort, int count, boolean capped) {
    }

    static AssistantBlock of(Type type) {
        return new AssistantBlock(type, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    static AssistantBlock listings(List<ListingCard> cards) {
        return new AssistantBlock(Type.LISTINGS, cards, null, null, null, null, null, null, null, null, null, null, null);
    }

    static AssistantBlock facts(ListingCard card, ListingFacts facts) {
        return new AssistantBlock(Type.LISTING_FACTS, List.of(card), facts, null, null, null, null, null, null, null,
                null, null, null);
    }

    static AssistantBlock comparison(List<ListingCard> cards, CompareListingsTool.Differences differences) {
        return new AssistantBlock(Type.COMPARISON, cards, null, differences, null, null, null, null, null, null, null,
                null, null);
    }

    static AssistantBlock searchLink(SearchLink link) {
        return new AssistantBlock(Type.SEARCH_LINK, null, null, null, link, null, null, null, null, null, null, null, null);
    }

    static AssistantBlock financing(FinancingTool.Simulation simulation) {
        return new AssistantBlock(Type.FINANCING, null, null, null, null, simulation, null, simulation.listingId(), null,
                null, null, null, null);
    }

    static AssistantBlock critAir(CritAirTool.Estimate estimate, UUID listingId) {
        return new AssistantBlock(Type.CRIT_AIR, null, null, null, null, null, estimate, listingId, null, null, null,
                null, null);
    }

    static AssistantBlock valuation(Valuation valuation) {
        return new AssistantBlock(Type.VALUATION, null, null, null, null, null, null, null, valuation, null, null, null,
                null);
    }

    static AssistantBlock help(List<HelpIndex.Passage> passages) {
        return new AssistantBlock(Type.HELP_PASSAGE, null, null, null, null, null, null, null, null, passages, null,
                null, null);
    }

    static AssistantBlock notUnderstood(List<String> words) {
        return new AssistantBlock(Type.NOT_UNDERSTOOD, null, null, null, null, null, null, null, null, null, words,
                null, null);
    }

    static AssistantBlock noAnswer(Plan.Reason reason, UUID listingId) {
        return new AssistantBlock(Type.NO_ANSWER, null, null, null, null, null, null, listingId, null, null, null,
                reason, null);
    }

    static AssistantBlock clarify(Plan.Question question) {
        return new AssistantBlock(Type.CLARIFY, null, null, null, null, null, null, null, null, null, null, null,
                question);
    }
}
