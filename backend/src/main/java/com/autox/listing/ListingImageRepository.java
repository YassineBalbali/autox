package com.autox.listing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    /**
     * Variants are fetch-joined because every caller renders them: without the join this is
     * one query for the images and then one per image for its renditions.
     */
    @Query("""
            select distinct i from ListingImage i
             left join fetch i.variants
             where i.listing.id = :listingId
             order by i.position asc
            """)
    List<ListingImage> findByListing(@Param("listingId") UUID listingId);

    @Query("""
            select distinct i from ListingImage i
             left join fetch i.variants
             where i.listing.id = :listingId
               and i.status = com.autox.listing.ImageStatus.READY
             order by i.position asc
            """)
    List<ListingImage> findReadyByListing(@Param("listingId") UUID listingId);

    @Query("""
            select i from ListingImage i
             left join fetch i.variants
             where i.id = :id and i.listing.id = :listingId
            """)
    Optional<ListingImage> findByIdAndListing(@Param("id") UUID id, @Param("listingId") UUID listingId);

    /** For callers outside a transaction, such as tests and the sweeper's logging. */
    @Query("select i from ListingImage i left join fetch i.variants where i.id = :id")
    Optional<ListingImage> findWithVariants(@Param("id") UUID id);

    /**
     * Counts everything not yet known to have failed, pending uploads included. A seller who
     * has requested twenty upload URLs has used their allowance, otherwise requesting URLs
     * without confirming them is an unbounded way around the cap.
     */
    @Query("""
            select count(i) from ListingImage i
             where i.listing.id = :listingId
               and i.status <> com.autox.listing.ImageStatus.FAILED
            """)
    long countUsableByListing(@Param("listingId") UUID listingId);

    @Query("select coalesce(max(i.position), -1) from ListingImage i where i.listing.id = :listingId")
    short findMaxPosition(@Param("listingId") UUID listingId);

    /** Abandoned upload slots: a URL was issued, the client never confirmed, the URL expired. */
    @Query("""
            select i from ListingImage i
             where i.status = com.autox.listing.ImageStatus.PENDING
               and i.uploadExpiresAt is not null
               and i.uploadExpiresAt < :before
            """)
    List<ListingImage> findAbandonedUploads(@Param("before") Instant before);
}
