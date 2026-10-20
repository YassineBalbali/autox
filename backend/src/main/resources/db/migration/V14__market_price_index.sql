-- The price indicator compares each listing with the other live listings of the same model
-- registered within two years of it (see MarketPrice). That lookup is by model, then a year
-- range, over live listings only: this index answers it with a range scan, whatever the
-- size of the table.
CREATE INDEX ix_listing_live_market ON listing (model_id, first_registration_year)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;

COMMENT ON INDEX ix_listing_live_market IS
    'Comparable listings for the price indicator: same model, nearby registration year.';
