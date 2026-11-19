package com.autox.assistant.tools;

import com.autox.api.error.ApiException;
import com.autox.search.ListingSearchService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The {@code get_listing} tool: one listing's facts, read through the public detail, so the
 * public API's visibility rule applies unchanged. A listing that is not online (expired, sold,
 * removed, its seller suspended) is {@code LISTING_UNAVAILABLE}, never stale facts.
 */
@Component
public class ListingFactsReader {

    private final ListingSearchService search;

    public ListingFactsReader(ListingSearchService search) {
        this.search = search;
    }

    public ToolResult<ListingFacts> read(UUID listingId) {
        return find(listingId)
                .<ToolResult<ListingFacts>>map(ToolResult::answer)
                .orElseGet(() -> ToolResult.refusal(ToolResult.Reason.LISTING_UNAVAILABLE, listingId));
    }

    /** The facts, or empty when the public API answers 404 for this id. */
    Optional<ListingFacts> find(UUID listingId) {
        try {
            return Optional.of(ListingFacts.from(search.detail(listingId)));
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
