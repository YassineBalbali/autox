package com.autox.listing.dto;

import com.autox.listing.DamageType;
import com.autox.listing.Listing;
import java.util.Arrays;
import java.util.List;

/**
 * A listing's declared damage, wherever the listing is shown; null on an undamaged one.
 *
 * @param running null only on a listing from before sellers were asked: see V20
 */
public record DamageView(List<DamageType> types, Boolean running, String description) {

    public static DamageView from(Listing listing) {
        return listing.isDamaged()
                ? new DamageView(List.copyOf(listing.getDamageTypes()), listing.getDamageRunning(),
                        listing.getDamageDescription())
                : null;
    }

    /** From the columns as a query reads them, for the same listing shown in search results. */
    public static DamageView of(boolean damaged, String[] types, Boolean running, String description) {
        return damaged
                ? new DamageView(Arrays.stream(types).map(DamageType::valueOf).sorted().toList(), running, description)
                : null;
    }
}
