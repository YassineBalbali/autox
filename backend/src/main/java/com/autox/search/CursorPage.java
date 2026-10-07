package com.autox.search;

import java.util.List;

/**
 * A page of search results.
 *
 * <p>No total count, deliberately. Counting the matches for an arbitrary filter combination
 * means a second full scan of the result set on every page, which costs more than the page
 * itself and grows with the catalogue. Infinite scroll — which is what a phone does — needs
 * {@code nextCursor} and nothing else.
 *
 * <p>When a result count does become a product requirement, the answer is a cached approximate
 * count per filter combination, or Elasticsearch, not a {@code COUNT(*)} on the hot path.
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
