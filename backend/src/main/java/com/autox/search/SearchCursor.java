package com.autox.search;

import com.autox.api.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * An opaque "resume from here" token: the sort value and id of the last row returned.
 *
 * <p>Opaque on purpose. It is base64 rather than two query parameters so that the fields it
 * carries can change — a new sort, a different tie-breaker — without a client having to
 * understand them or an old client constructing one by hand.
 *
 * <p>It records which sort it belongs to, because a cursor taken from a price-sorted page is
 * meaningless against a date-sorted query. Changing sort restarts pagination, and saying so
 * with a 400 is better than silently returning an arbitrary slice.
 */
public record SearchCursor(SearchSort sort, String sortValue, UUID id) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = String.join(SEPARATOR, VERSION, sort.name(), sortValue, id.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static SearchCursor decode(String encoded, SearchSort expectedSort) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.invalidCursor("The pagination cursor is not valid.");
        }

        String[] parts = raw.split("\\" + SEPARATOR, 4);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw ApiException.invalidCursor("The pagination cursor is not valid.");
        }

        SearchSort cursorSort;
        try {
            cursorSort = SearchSort.valueOf(parts[1]);
        } catch (IllegalArgumentException unknown) {
            throw ApiException.invalidCursor("The pagination cursor is not valid.");
        }

        if (cursorSort != expectedSort) {
            throw ApiException.invalidCursor("This cursor belongs to the " + cursorSort
                    + " ordering but the request asked for " + expectedSort
                    + ". Changing the sort order starts a new result set.");
        }

        try {
            return new SearchCursor(cursorSort, parts[2], UUID.fromString(parts[3]));
        } catch (IllegalArgumentException malformed) {
            throw ApiException.invalidCursor("The pagination cursor is not valid.");
        }
    }

    /** Builds the cursor that resumes after a given row. */
    public static SearchCursor after(SearchSort sort, Instant publishedAt, long price, int mileage, UUID id) {
        String value = switch (sort) {
            case NEWEST -> publishedAt == null ? "0" : Long.toString(publishedAt.toEpochMilli());
            case PRICE_ASC, PRICE_DESC -> Long.toString(price);
            case MILEAGE_ASC -> Integer.toString(mileage);
        };
        return new SearchCursor(sort, value, id);
    }

    /** The value as the SQL comparison needs it: a timestamp for dates, a number otherwise. */
    public Object typedSortValue() {
        return switch (sort) {
            case NEWEST -> java.sql.Timestamp.from(Instant.ofEpochMilli(parseLong()));
            case PRICE_ASC, PRICE_DESC -> parseLong();
            case MILEAGE_ASC -> (int) parseLong();
        };
    }

    private long parseLong() {
        try {
            return Long.parseLong(sortValue);
        } catch (NumberFormatException malformed) {
            throw ApiException.invalidCursor("The pagination cursor is not valid.");
        }
    }
}
