package com.autox.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The orderings public search supports.
 *
 * <p>Each one is a keyset sort, so each needs a column that is non-null on every visible
 * listing, plus {@code id} as a tie-breaker to make the order total. Without the tie-breaker
 * two listings at the same price would have an undefined relative order, and a cursor sitting
 * between them would either skip or repeat rows.
 */
@Schema(enumAsRef = true)
public enum SearchSort {

    /** Default. Uses ix_listing_live_published (published_at DESC, id DESC). */
    NEWEST("l.published_at", Direction.DESC),

    PRICE_ASC("l.price_amount", Direction.ASC),

    PRICE_DESC("l.price_amount", Direction.DESC),

    MILEAGE_ASC("l.mileage_km", Direction.ASC),

    /** The rest are for leasing offers alone: on a sale their columns are null. */
    MONTHLY_ASC("l.leasing_monthly", Direction.ASC),

    MONTHLY_DESC("l.leasing_monthly", Direction.DESC),

    DURATION_ASC("l.leasing_duration_months", Direction.ASC),

    MILEAGE_ALLOWANCE_DESC("l.leasing_mileage_per_year", Direction.DESC);

    private final String column;
    private final Direction direction;

    SearchSort(String column, Direction direction) {
        this.column = column;
        this.direction = direction;
    }

    public boolean leasingOnly() {
        return column.startsWith("l.leasing_");
    }

    public String column() {
        return column;
    }

    public boolean descending() {
        return direction == Direction.DESC;
    }

    /** {@code <} for a descending sort, {@code >} for ascending: "everything after the cursor". */
    public String keysetComparator() {
        return descending() ? "<" : ">";
    }

    public String orderByClause() {
        String keyword = descending() ? "DESC" : "ASC";
        return column + " " + keyword + ", l.id " + keyword;
    }

    private enum Direction {
        ASC, DESC
    }
}
