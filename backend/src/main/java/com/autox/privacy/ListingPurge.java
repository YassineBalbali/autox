package com.autox.privacy;

import com.autox.storage.ObjectStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes listings for good: the rows and, once the transaction has committed, the photos in
 * storage. For an account that is deleted, and for listings their seller deleted long enough ago
 * ({@link DataRetentionSweeper}).
 *
 * <p>With a listing go its photos and their variants, its equipment, its price history, its
 * reports and its place in saved-search alerts (by cascade). Its conversations stay, for the other
 * party, without it; so do the moderators' decisions about it, without their free-text note,
 * which may describe the seller.
 *
 * <p>Storage after the commit: a rollback then leaves the photos where the rows that name them
 * still are. A storage failure leaves objects nobody references, which is logged; the reverse,
 * rows pointing at deleted objects, would be served as broken images.
 */
@Component
class ListingPurge {

    private static final Logger log = LoggerFactory.getLogger(ListingPurge.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStorage storage;

    ListingPurge(NamedParameterJdbcTemplate jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** What was deleted. */
    record Purged(int listings, int objects) {
    }

    /** Must run inside a transaction. */
    Purged purge(Collection<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return new Purged(0, 0);
        }
        MapSqlParameterSource ids = new MapSqlParameterSource("ids", listingIds);
        List<String> keys = new ArrayList<>(jdbc.queryForList("""
                SELECT storage_key FROM listing_image WHERE listing_id IN (:ids)
                UNION ALL
                SELECT v.storage_key FROM listing_image_variant v
                  JOIN listing_image i ON i.id = v.image_id
                 WHERE i.listing_id IN (:ids)""", ids, String.class));

        jdbc.update("UPDATE moderation_action SET note = NULL WHERE listing_id IN (:ids)", ids);
        int deleted = jdbc.update("DELETE FROM listing WHERE id IN (:ids)", ids);

        if (!keys.isEmpty()) {
            afterCommit(() -> {
                try {
                    storage.deleteAll(keys);
                } catch (RuntimeException e) {
                    log.warn("{} listing(s) deleted but {} stored object(s) could not be: {}",
                            deleted, keys.size(), e.getMessage());
                }
            });
        }
        return new Purged(deleted, keys.size());
    }

    /** Other stored objects of something deleted in this transaction, such as a dealer's logo. */
    void deleteObjectsAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        afterCommit(() -> {
            try {
                storage.deleteAll(keys);
            } catch (RuntimeException e) {
                log.warn("{} stored object(s) could not be deleted: {}", keys.size(), e.getMessage());
            }
        });
    }

    static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
