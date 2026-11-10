-- AutoX V29: a dealer's logo upload slot, kept until it is confirmed or swept.
--
-- A listing photo's slot is its PENDING listing_image row, and ListingImageSweeper deletes the
-- expired ones with their objects. A logo had no row until it was confirmed, so an upload that
-- was never confirmed stayed in storage, referenced by nothing, for ever. This is that row: written
-- when the slot is issued, deleted when the logo is made, and swept with its object once the
-- slot has expired, by the same scheduled job.

CREATE TABLE dealer_logo_upload (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    storage_key VARCHAR(512) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_dealer_logo_upload_expires ON dealer_logo_upload (expires_at);
CREATE INDEX ix_dealer_logo_upload_user ON dealer_logo_upload (user_id);

COMMENT ON TABLE dealer_logo_upload IS
    'A logo upload slot not yet confirmed. Deleted on confirmation; swept with its stored object once expired.';
