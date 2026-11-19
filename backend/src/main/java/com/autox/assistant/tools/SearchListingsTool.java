package com.autox.assistant.tools;

import com.autox.listing.OfferType;
import com.autox.reference.Currency;
import com.autox.reference.CurrencyRepository;
import com.autox.search.ListingSearchCriteria;
import com.autox.search.ListingSearchService;
import com.autox.search.ResultCount;
import com.autox.search.SearchSort;
import com.autox.search.interpret.InterpretResponse;
import com.autox.search.interpret.InterpretationValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The {@code search_listings} tool: the smart search's criteria, checked by the smart search's own
 * validator (not a copy of it), then the public search.
 *
 * <p>The criteria object is the one {@code POST /search/interpret} asks the model for, so
 * everything docs/ai-search.md says about validation holds here: catalogue resolution, enums,
 * bounds, the place quoted from the message, and what was dropped reported in
 * {@code notUnderstood}.
 *
 * <p>Not counted in the dashboard's search usage, which counts searches made on the results page.
 */
@Component
public class SearchListingsTool {

    /** Results a turn shows; the rest are one click away on the ordinary results page. */
    public static final int MAX_RESULTS = 5;
    static final String CURRENCY = "EUR";

    private final InterpretationValidator validator;
    private final ListingSearchService search;
    private final ListingFactsReader reader;
    private final CurrencyRepository currencies;

    public SearchListingsTool(InterpretationValidator validator, ListingSearchService search,
                              ListingFactsReader reader, CurrencyRepository currencies) {
        this.validator = validator;
        this.search = search;
        this.reader = reader;
        this.currencies = currencies;
    }

    /**
     * @param criteria the model's criteria object, unchecked
     * @param message  what the user wrote this turn, which a place or a quotation must come from
     * @param sort     the order asked for; null, or a leasing sort on a sale search, is the newest first
     */
    public Outcome search(JsonNode criteria, String message, SearchSort sort) {
        int minorUnit = currencies.findById(CURRENCY).map(Currency::getMinorUnit).orElse((short) 2);
        InterpretResponse checked = validator.validate(criteria, message, Year.now().getValue(), minorUnit);
        ListingSearchCriteria filters = checked.criteria();
        SearchSort order = sort == null || (sort.leasingOnly() && filters.offer() != OfferType.LEASING)
                ? SearchSort.NEWEST : sort;

        List<ListingFacts> results = search.search(filters, order, null, MAX_RESULTS).items().stream()
                // A listing gone between the search and its detail is left out, not shown stale.
                .map(summary -> reader.find(summary.id()))
                .flatMap(Optional::stream)
                .toList();
        return new Outcome(filters, order, checked.notUnderstood(), results, search.count(filters));
    }

    /**
     * @param criteria      the validated filters, for "Voir les N résultats" on the results page
     * @param notUnderstood the parts of the message that became no filter, quoted from it
     * @param results       at most {@link #MAX_RESULTS}; empty is the plan's {@code NO_RESULTS}
     */
    public record Outcome(ListingSearchCriteria criteria, SearchSort sort, List<String> notUnderstood,
                          List<ListingFacts> results, ResultCount count) {
    }
}
