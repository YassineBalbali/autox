package com.autox.dealer;

import io.swagger.v3.oas.annotations.media.Schema;

/** How the directory of professionals is ordered. */
@Schema(enumAsRef = true)
public enum DealerSort {
    /** Most listings online first, then by name. */
    LISTINGS,
    /** By name, accents and case aside. */
    NAME
}
