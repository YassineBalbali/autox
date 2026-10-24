package com.autox.search;

import com.autox.search.dto.PublicListingDetail;
import com.autox.search.dto.PublicListingSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The buyer-facing endpoints. No authentication: this is the shop window, and it has to be
 * crawlable and linkable.
 *
 * <p>Only ACTIVE, non-expired, non-deleted, non-rejected listings exist here. Anything else
 * answers 404, including a listing the caller happens to own — the seller's own view of it
 * lives under {@code /api/v1/listings/mine/{id}}.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Listings (public)", description = "The buyer-facing shop window. No authentication.")
@RestController
@RequestMapping("/api/v1/listings")
@Validated
public class PublicListingController {

    private static final int MAX_PAGE_SIZE = 60;

    private final ListingSearchService searchService;

    public PublicListingController(ListingSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Search the live listings. Every filter is optional; no filters means everything visible.
     *
     * <p>Paginated by cursor, not by page number: {@code nextCursor} from one response is
     * {@code cursor} in the next. There is no page number, because offset paging over a table
     * being written to skips and repeats rows. The number of matches is a separate call,
     * {@code GET /api/v1/listings/count}, so that no page pays for it.
     */
    @GetMapping
    public CursorPage<PublicListingSummary> search(
            @Valid ListingSearchCriteria criteria,
            @RequestParam(defaultValue = "NEWEST") SearchSort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "24") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return searchService.search(criteria, sort, cursor, size);
    }

    /**
     * How many listings match the same filters {@code GET /api/v1/listings} takes.
     *
     * <p>Exact up to 10 000; beyond that {@code capped} is true and {@code count} is the
     * cap, to be shown as "more than 10 000". The bound keeps the cost constant however
     * large the catalogue grows. Call it once when the filters change — not per page —
     * and do not make the result list wait for it.
     */
    @GetMapping("/count")
    public ResultCount count(@Valid ListingSearchCriteria criteria) {
        return searchService.count(criteria);
    }

    /**
     * One listing, as a buyer sees it: full description, equipment codes and the image gallery
     * in order, each image with its three size variants.
     *
     * <p>404 unless the listing is ACTIVE, not rejected, not soft-deleted and not past its
     * expiry — including when the caller happens to be its owner, who reads it at
     * {@code /api/v1/listings/mine/{id}} instead. The VIN, the seller's email and the exact
     * coordinates are not part of this response.
     */
    @GetMapping("/{id}")
    public PublicListingDetail detail(@PathVariable UUID id) {
        return searchService.detail(id);
    }

    /**
     * "Annonces similaires": three to six live listings like this one, best first, as search
     * result cards; an empty list when there are fewer than three good matches, never padding.
     *
     * <p>The same model registered within three years and priced within 40 % first, then the
     * same body type within 20 % of the price. Never the listing itself, and at most one of a seller's copies of the
     * same car. 404 when the listing is not public, as for the detail.
     */
    @GetMapping("/{id}/similar")
    public List<PublicListingSummary> similar(@PathVariable UUID id) {
        return searchService.similar(id);
    }
}
