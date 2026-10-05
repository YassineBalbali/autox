package com.autox.listing;

/** Mirrors ck_listing_condition. */
public enum VehicleCondition {
    NEW,
    USED,
    /** Accident-damaged or non-running. Kept distinct so it can be excluded from search by default. */
    DAMAGED
}
