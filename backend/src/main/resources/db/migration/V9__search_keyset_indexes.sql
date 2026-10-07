-- AutoX V9 — indexes that actually serve the public search sorts.
--
-- Measured against 20 000 published listings (SearchQueryPlanTest), the plans showed:
--
--   NEWEST       Index Scan using ix_listing_live_published, with the keyset row comparison
--                as an Index Cond. Correct, and left alone.
--
--   PRICE_ASC    Seq Scan over every visible listing plus a top-N heapsort. The existing
--   PRICE_DESC   ix_listing_live_price leads with price_currency_code, and search does not
--                constrain the currency — so the index could not be used for ordering at all.
--
--   MILEAGE_ASC  Index Scan using ix_listing_live_mileage, but followed by an Incremental
--                Sort because the index stops at mileage_km and the sort needs (mileage_km,
--                id) to be total.
--
-- A keyset sort needs its index to end on the tie-breaker. These two do.
-- A btree is scannable in both directions, so one ascending index serves PRICE_ASC and
-- PRICE_DESC alike.

DROP INDEX ix_listing_live_price;
CREATE INDEX ix_listing_live_price ON listing (price_amount, id)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;

DROP INDEX ix_listing_live_mileage;
CREATE INDEX ix_listing_live_mileage ON listing (mileage_km, id)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;

COMMENT ON INDEX ix_listing_live_price IS
    'Keyset sort by price. Currency is deliberately not the leading column: sorting prices across currencies is meaningless anyway, and leading with it made the index unusable for the sort.';
COMMENT ON INDEX ix_listing_live_mileage IS
    'Keyset sort by mileage. Ends on id so the ordering is total and no sort node is needed.';
