package com.autox.listing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The moderation trail. Rows are only ever inserted and read: there is no update and no
 * delete, here or anywhere else, because a trail that can be edited is not one.
 */
public interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {

    /**
     * Every action taken on the given listings, oldest first.
     *
     * <p>Takes a collection so that a page of listings costs one query rather than one per
     * row. The moderator is fetch-joined because every caller prints their name. Ordered by
     * id as well as by time: ids are UUIDv7, so two actions in the same millisecond still
     * come back in the order they were written.
     */
    @Query("""
            select a from ModerationAction a
              join fetch a.admin
             where a.listing.id in :listingIds
             order by a.createdAt asc, a.id asc
            """)
    List<ModerationAction> findTrails(@Param("listingIds") Collection<UUID> listingIds);
}
