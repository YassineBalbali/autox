package com.autox.listing;

import com.autox.common.CreatedEntity;
import com.autox.reference.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One asking price a listing carried, from {@link #getCreatedAt()} onward.
 *
 * <p>Append-only: nothing updates or deletes a row. The series for a listing is the
 * {@code INITIAL} row at first publication followed by one {@code SELLER_EDIT} per
 * change, so the price at any past instant is the last row at or before it.
 *
 * <p>Not exposed by any endpoint yet. It exists because price is otherwise overwritten,
 * and the history is the input to price estimation — which cannot be reconstructed after
 * the fact.
 */
@Entity
@Table(name = "listing_price_history")
public class ListingPriceHistory extends CreatedEntity {

    /**
     * The listing id rather than a {@code @ManyToOne}: nothing here navigates to the
     * listing, and a plain column keeps recording a price from turning into a reason to
     * load the aggregate.
     */
    @Column(name = "listing_id", nullable = false, updatable = false)
    private java.util.UUID listingId;

    @Column(name = "price_amount", nullable = false, updatable = false)
    private long priceAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_currency_code", nullable = false, updatable = false)
    private Currency priceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 16, nullable = false, updatable = false)
    private PriceChangeReason reason;

    protected ListingPriceHistory() {
    }

    public static ListingPriceHistory of(Listing listing, PriceChangeReason reason) {
        ListingPriceHistory row = new ListingPriceHistory();
        row.listingId = listing.getId();
        row.priceAmount = listing.getPriceAmount();
        row.priceCurrency = listing.getPriceCurrency();
        row.reason = reason;
        return row;
    }

    public java.util.UUID getListingId() {
        return listingId;
    }

    public long getPriceAmount() {
        return priceAmount;
    }

    public Currency getPriceCurrency() {
        return priceCurrency;
    }

    public PriceChangeReason getReason() {
        return reason;
    }
}
