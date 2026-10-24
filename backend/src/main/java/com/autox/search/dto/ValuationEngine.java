package com.autox.search.dto;

/**
 * One engine of a model as it is listed on AutoX: a fuel and a power, with the version name
 * sellers most often give it, and how many live listings have it.
 *
 * @param exampleVersion the most common version text among those listings, which may name a
 *                       trim level as well as the engine; null when none gave one
 */
public record ValuationEngine(String fuelType, int powerHp, String exampleVersion, int listingCount) {
}
