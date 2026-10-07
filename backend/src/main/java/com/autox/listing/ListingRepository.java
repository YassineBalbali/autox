package com.autox.listing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, UUID> {

    /** Soft-deleted rows are invisible to every path except an explicit audit query. */
    @Query("select l from Listing l where l.id = :id and l.deletedAt is null")
    Optional<Listing> findActiveById(@Param("id") UUID id);

    @Query("select l from Listing l where l.seller.id = :sellerId and l.deletedAt is null")
    Page<Listing> findBySeller(@Param("sellerId") UUID sellerId, Pageable pageable);

    @Query("select l from Listing l where l.seller.id = :sellerId and l.status = :status and l.deletedAt is null")
    Page<Listing> findBySellerAndStatus(@Param("sellerId") UUID sellerId,
                                        @Param("status") ListingStatus status,
                                        Pageable pageable);

    /** Backs the active-listing quota. Counts only what {@link ListingStatus#occupiesQuota()} says counts. */
    @Query("""
            select count(l) from Listing l
             where l.seller.id = :sellerId
               and l.status = com.autox.listing.ListingStatus.ACTIVE
               and l.deletedAt is null
            """)
    long countActiveBySeller(@Param("sellerId") UUID sellerId);

    /**
     * Expiry sweeper. A bulk update rather than loaded entities: this runs over
     * everything that fell due, and none of it needs to be in the persistence context.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Listing l
               set l.status = com.autox.listing.ListingStatus.EXPIRED,
                   l.updatedAt = :now
             where l.status = com.autox.listing.ListingStatus.ACTIVE
               and l.expiresAt is not null
               and l.expiresAt <= :now
               and l.deletedAt is null
            """)
    int expireDueListings(@Param("now") Instant now);
}
