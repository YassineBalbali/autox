-- AutoX V7 — free-text location fallback for listings.
--
-- V4 required every listing to reference a geo_place row. With the gazetteer
-- deferred, that makes listing creation impossible: there is nothing to reference.
-- This mirrors the escape hatch the catalogue already has — a listing identifies its
-- location either by gazetteer reference or by postal code plus city as text.
--
-- When the gazetteer lands, a backfill resolves the text rows to geo_place ids and
-- fills latitude/longitude; nothing about the API contract changes.

ALTER TABLE listing ALTER COLUMN geo_place_id DROP NOT NULL;
-- Coordinates come from the referenced place, so they are unknown until it is known.
ALTER TABLE listing ALTER COLUMN latitude DROP NOT NULL;
ALTER TABLE listing ALTER COLUMN longitude DROP NOT NULL;

ALTER TABLE listing ADD COLUMN postal_code VARCHAR(12);
ALTER TABLE listing ADD COLUMN city VARCHAR(120);

ALTER TABLE listing ADD CONSTRAINT ck_listing_location_known CHECK (
    geo_place_id IS NOT NULL OR (postal_code IS NOT NULL AND city IS NOT NULL));

-- Latitude and longitude are only ever set together.
ALTER TABLE listing ADD CONSTRAINT ck_listing_coordinates_paired CHECK (
    (latitude IS NULL) = (longitude IS NULL));

COMMENT ON COLUMN listing.postal_code IS
    'Set only when geo_place_id is null. Feeds the same admin queue as a catalogue gap.';
COMMENT ON COLUMN listing.city IS 'Seller-entered city, used until the gazetteer can resolve it.';

-- Queue of listings whose location is still unresolved text.
CREATE INDEX ix_listing_location_gap ON listing (created_at) WHERE geo_place_id IS NULL;
