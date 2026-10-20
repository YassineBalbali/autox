package com.autox.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where a listing's price sits against comparable listings. See {@link MarketPrice} for what
 * "comparable" means and where the lines are drawn.
 */
@Schema(enumAsRef = true)
public enum PriceLevel {
    /** At least {@link MarketPrice#DEAL_MARGIN} below the median of its comparables. */
    GOOD_DEAL,
    /** Within the margin either side of the median. */
    MARKET,
    /** At least {@link MarketPrice#DEAL_MARGIN} above the median. */
    ABOVE_MARKET
}
