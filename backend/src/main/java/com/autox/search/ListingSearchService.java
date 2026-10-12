package com.autox.search;

import com.autox.api.error.ApiException;
import com.autox.listing.Listing;
import com.autox.listing.ListingImageRepository;
import com.autox.listing.ListingRepository;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationStatus;
import com.autox.listing.dto.ImageResponse;
import com.autox.search.dto.PublicListingDetail;
import com.autox.search.dto.PublicListingSummary;
import com.autox.storage.ObjectStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public, unauthenticated reads: the search results page and the detail page. */
@Service
public class ListingSearchService {

    /** One more than the page, to learn whether another page exists without counting. */
    private static final int LOOKAHEAD = 1;

    /** Past this, the count is reported as "more than". See {@code countUpTo}. */
    static final int COUNT_CAP = 10_000;

    private final ListingSearchRepository searchRepository;
    private final ListingRepository listings;
    private final ListingImageRepository images;
    private final ObjectStorage storage;

    public ListingSearchService(ListingSearchRepository searchRepository,
                                ListingRepository listings,
                                ListingImageRepository images,
                                ObjectStorage storage) {
        this.searchRepository = searchRepository;
        this.listings = listings;
        this.images = images;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public CursorPage<PublicListingSummary> search(ListingSearchCriteria criteria, SearchSort sort,
                                                   String encodedCursor, int size) {
        if (criteria.hasPartialBoundingBox()) {
            throw ApiException.validationFailed(
                    "A bounding box needs all four of latMin, latMax, lonMin and lonMax.");
        }

        SearchCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                ? null
                : SearchCursor.decode(encodedCursor, sort);

        // Fetching one extra row is how "is there more?" is answered without a COUNT.
        List<ListingSearchRepository.SearchRow> rows =
                searchRepository.search(criteria, sort, cursor, size + LOOKAHEAD);

        boolean hasMore = rows.size() > size;
        List<ListingSearchRepository.SearchRow> page = hasMore ? rows.subList(0, size) : rows;

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ListingSearchRepository.SearchRow last = page.getLast();
            nextCursor = SearchCursor.after(sort, last.publishedAt(), last.priceAmount(),
                    last.mileageKm(), last.id()).encode();
        }

        return CursorPage.of(toSummaries(page), nextCursor, sort, page.size());
    }

    /**
     * The number of listings matching a search, exact up to {@link #COUNT_CAP}.
     *
     * <p>Its own call rather than a field on the page: it is asked once per filter change,
     * not once per page, and a results list should not wait on it.
     */
    @Transactional(readOnly = true)
    public ResultCount count(ListingSearchCriteria criteria) {
        if (criteria.hasPartialBoundingBox()) {
            throw ApiException.validationFailed(
                    "A bounding box needs all four of latMin, latMax, lonMin and lonMax.");
        }
        int counted = searchRepository.countUpTo(criteria, COUNT_CAP);
        return new ResultCount(Math.min(counted, COUNT_CAP), counted > COUNT_CAP);
    }

    @Transactional(readOnly = true)
    public PublicListingDetail detail(UUID listingId) {
        Listing listing = listings.findActiveById(listingId)
                .orElseThrow(() -> ApiException.notFound("Listing"));

        if (!isPubliclyVisible(listing)) {
            // A draft, paused, sold or expired listing is simply not there as far as the
            // public API is concerned — the same answer as an id that never existed.
            throw ApiException.notFound("Listing");
        }

        List<ImageResponse> gallery = images.findReadyByListing(listingId).stream()
                .map(image -> ImageResponse.from(image, storage::readUrl))
                .toList();

        return PublicListingDetail.from(listing, gallery);
    }

    /**
     * Mirrors the SQL visibility predicate, including the expiry check: between sweeper runs a
     * listing can be ACTIVE with an expiry in the past, and it must not be readable then.
     */
    private static boolean isPubliclyVisible(Listing listing) {
        return listing.getStatus() == ListingStatus.ACTIVE
                && listing.getModerationStatus() != ModerationStatus.REJECTED
                && listing.getDeletedAt() == null
                && (listing.getExpiresAt() == null || listing.getExpiresAt().isAfter(Instant.now()));
    }

    private List<PublicListingSummary> toSummaries(List<ListingSearchRepository.SearchRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = new ArrayList<>(rows.size());
        rows.forEach(row -> ids.add(row.id()));
        // One query for every cover image on the page, rather than one per card.
        Map<UUID, Map<String, ListingSearchRepository.CoverVariant>> covers =
                searchRepository.findCoverImages(ids);

        List<PublicListingSummary> summaries = new ArrayList<>(rows.size());
        for (ListingSearchRepository.SearchRow row : rows) {
            summaries.add(new PublicListingSummary(
                    row.id(),
                    row.title(),
                    row.priceAmount(),
                    row.currencyCode(),
                    row.priceNegotiable(),
                    row.make(),
                    row.model(),
                    row.version(),
                    row.firstRegistrationYear(),
                    row.firstRegistrationMonth(),
                    row.mileageKm(),
                    row.fuelType(),
                    row.gearbox(),
                    row.bodyType(),
                    row.drivetrain(),
                    row.powerHp(),
                    row.condition(),
                    row.countryCode(),
                    row.city(),
                    row.postalCode(),
                    new PublicListingSummary.SellerCard(row.sellerId(), row.sellerName(), row.sellerRole()),
                    coverOf(covers.get(row.id())),
                    row.publishedAt()));
        }
        return summaries;
    }

    private Map<String, PublicListingSummary.ImageVariantView> coverOf(
            Map<String, ListingSearchRepository.CoverVariant> variants) {

        if (variants == null || variants.isEmpty()) {
            return Map.of();
        }
        Map<String, PublicListingSummary.ImageVariantView> views = new LinkedHashMap<>();
        variants.forEach((name, variant) -> views.put(name, new PublicListingSummary.ImageVariantView(
                storage.readUrl(variant.storageKey()), variant.width(), variant.height())));
        return views;
    }

    /** Exposed for the plan checks in the tests; not reachable through the API. */
    public String explainSearch(ListingSearchCriteria criteria, SearchSort sort, String encodedCursor, int size) {
        SearchCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                ? null
                : SearchCursor.decode(encodedCursor, sort);
        return searchRepository.explain(criteria, sort, cursor, size + LOOKAHEAD);
    }
}
