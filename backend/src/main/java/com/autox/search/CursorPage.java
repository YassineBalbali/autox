package com.autox.search;

import java.util.List;

/**
 * A page of search results.
 *
 * <p>No total count on the page, deliberately. Counting the matches for an arbitrary filter
 * combination on every page would cost more than the page itself. The count is its own
 * endpoint — {@code GET /api/v1/listings/count}, see {@link ResultCount} — asked once per
 * filter change and bounded by a cap, so it is off the pagination path entirely.
 */
public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        SearchSort sort,
        int size) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor, SearchSort sort, int size) {
        return new CursorPage<>(items, nextCursor, nextCursor != null, sort, size);
    }
}
