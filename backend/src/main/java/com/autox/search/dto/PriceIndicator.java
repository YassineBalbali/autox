package com.autox.search.dto;

import com.autox.search.PriceLevel;

/**
 * How a listing's price compares with the market, and what the comparison rests on.
 *
 * <p>Absent (null on the listing) when there is nothing honest to say: fewer comparable
 * listings than the threshold, a new car, or a vehicle not identified from the catalogue.
 * The median and the count are given with the level so a buyer can see what it is based on
 * — "Bonne affaire" with no evidence behind it is a sales pitch.
 *
 * @param level             the stable code; the frontend words it
 * @param medianPriceAmount median price of the comparable listings, in minor units of the
 *                          listing's currency
 * @param comparableCount   how many listings the median was taken over, the listing itself
 *                          not included
 */
public record PriceIndicator(PriceLevel level, long medianPriceAmount, int comparableCount) {
}
