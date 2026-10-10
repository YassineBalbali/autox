package com.autox.listing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingPriceHistoryRepository extends JpaRepository<ListingPriceHistory, UUID> {

    /**
     * The full series for one listing. Unbounded by design and by exception: a listing's
     * price changes are a handful of rows, there is no endpoint behind this, and a page
     * of a time series is not a useful thing to hold.
     */
    @Query("""
            select h from ListingPriceHistory h
             where h.listingId = :listingId
             order by h.createdAt, h.id
            """)
    List<ListingPriceHistory> findSeries(@Param("listingId") UUID listingId);
}
