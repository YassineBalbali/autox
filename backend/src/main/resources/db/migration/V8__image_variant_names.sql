-- AutoX V8 — name the image variants after the roles they serve.
--
-- V4 guessed at THUMBNAIL/SMALL/MEDIUM/LARGE, which says how big a rendition is but not
-- what it is for. THUMBNAIL/CARD/FULL names the three places an image is actually shown:
-- the gallery strip, a search-result card, and the full-screen viewer. A size-based name
-- goes stale the moment a layout changes; a role-based one does not.
--
-- No rows exist yet, so this is a constraint swap rather than a data migration.

ALTER TABLE listing_image_variant DROP CONSTRAINT ck_listing_image_variant;
ALTER TABLE listing_image_variant ADD CONSTRAINT ck_listing_image_variant
    CHECK (variant IN ('THUMBNAIL', 'CARD', 'FULL'));

COMMENT ON COLUMN listing_image_variant.variant IS
    'THUMBNAIL: gallery strip and list rows. CARD: search-result card and detail default. FULL: full-screen viewer.';
