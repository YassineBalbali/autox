package com.autox.assistant.tools;

import com.autox.api.error.ApiException;
import com.autox.search.ListingSearchService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The {@code similar_listings} tool: the listing page's "Annonces similaires", same rule
 * (docs/similar-listings.md), at most five. An empty list is an answer: fewer than three good
 * matches, never padding.
 */
@Component
public class SimilarListingsTool {

    public static final int MAX_RESULTS = 5;

    private final ListingSearchService search;
    private final ListingFactsReader reader;

    public SimilarListingsTool(ListingSearchService search, ListingFactsReader reader) {
        this.search = search;
        this.reader = reader;
    }

    public ToolResult<List<ListingFacts>> similar(UUID listingId) {
        try {
            return ToolResult.answer(search.similar(listingId).stream()
                    .limit(MAX_RESULTS)
                    .map(summary -> reader.find(summary.id()))
                    .flatMap(Optional::stream)
                    .toList());
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return ToolResult.refusal(ToolResult.Reason.LISTING_UNAVAILABLE, listingId);
            }
            throw e;
        }
    }
}
