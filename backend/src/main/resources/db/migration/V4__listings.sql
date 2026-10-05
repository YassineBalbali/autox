-- AutoX V4 — listings, photos, equipment links, moderation trail.

-- Composite unique keys so a listing's (make, model) and (model, trim) pairs
-- can be validated by the database rather than by application code.
ALTER TABLE vehicle_model ADD CONSTRAINT ux_vehicle_model_make_id UNIQUE (make_id, id);
ALTER TABLE vehicle_trim  ADD CONSTRAINT ux_vehicle_trim_model_id UNIQUE (model_id, id);

CREATE TABLE listing (
    id                 UUID         PRIMARY KEY,
    seller_id          UUID         NOT NULL REFERENCES app_user (id),

    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    moderation_status  VARCHAR(16)  NOT NULL DEFAULT 'NOT_REVIEWED',

    title              VARCHAR(140) NOT NULL,
    description        TEXT,

    -- Minor units of price_currency_code (EUR cents), never a float.
    price_amount       BIGINT       NOT NULL,
    price_currency_code CHAR(3)     NOT NULL REFERENCES currency (code),
    price_negotiable   BOOLEAN      NOT NULL DEFAULT FALSE,
    vat_deductible     BOOLEAN,

    country_code       CHAR(2)      NOT NULL REFERENCES country (code),
    geo_place_id       UUID         NOT NULL REFERENCES geo_place (id),
    latitude           NUMERIC(9, 6) NOT NULL,
    longitude          NUMERIC(9, 6) NOT NULL,
    hide_exact_location BOOLEAN     NOT NULL DEFAULT TRUE,

    -- Catalogue reference, with a free-text escape hatch for vehicles we do not
    -- yet carry. Exactly one side of each pair is populated.
    make_id            UUID         REFERENCES vehicle_make (id),
    model_id           UUID         REFERENCES vehicle_model (id),
    trim_id            UUID         REFERENCES vehicle_trim (id),
    make_text          VARCHAR(80),
    model_text         VARCHAR(120),
    version_text       VARCHAR(160),
    catalog_gap_resolved_at TIMESTAMPTZ,

    first_registration_year  SMALLINT NOT NULL,
    first_registration_month SMALLINT,
    mileage_km         INTEGER      NOT NULL,

    fuel_type          VARCHAR(24)  NOT NULL,
    gearbox            VARCHAR(24)  NOT NULL,
    body_type          VARCHAR(24),
    drivetrain         VARCHAR(8),
    power_hp           SMALLINT,
    power_kw           SMALLINT,
    engine_displacement_cc INTEGER,
    doors              SMALLINT,
    seats              SMALLINT,

    vehicle_condition  VARCHAR(16)  NOT NULL DEFAULT 'USED',
    exterior_color     VARCHAR(16),
    color_text         VARCHAR(60),
    metallic_paint     BOOLEAN,
    interior_material  VARCHAR(16),
    owner_count        SMALLINT,
    service_history    BOOLEAN,
    warranty_months    SMALLINT,
    technical_inspection_valid_until DATE,
    co2_emissions_g_km SMALLINT,
    emission_class     VARCHAR(16),
    vin                VARCHAR(17),

    view_count         BIGINT       NOT NULL DEFAULT 0,

    published_at       TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ,
    sold_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_listing_make_model FOREIGN KEY (make_id, model_id)
        REFERENCES vehicle_model (make_id, id),
    CONSTRAINT fk_listing_model_trim FOREIGN KEY (model_id, trim_id)
        REFERENCES vehicle_trim (model_id, id),

    CONSTRAINT ck_listing_status CHECK (status IN
        ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'PAUSED', 'SOLD', 'EXPIRED', 'REMOVED')),
    CONSTRAINT ck_listing_moderation_status CHECK (moderation_status IN
        ('NOT_REVIEWED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_listing_description CHECK (description IS NULL OR length(description) <= 10000),
    CONSTRAINT ck_listing_price CHECK (price_amount BETWEEN 0 AND 1000000000),

    -- Make and model must each be identified either by catalogue id or by text.
    CONSTRAINT ck_listing_make_known  CHECK (make_id IS NOT NULL OR make_text IS NOT NULL),
    CONSTRAINT ck_listing_model_known CHECK (model_id IS NOT NULL OR model_text IS NOT NULL),
    -- A catalogue id and its text twin are mutually exclusive.
    CONSTRAINT ck_listing_make_exclusive  CHECK (make_id IS NULL OR make_text IS NULL),
    CONSTRAINT ck_listing_model_exclusive CHECK (model_id IS NULL OR model_text IS NULL),
    -- MATCH SIMPLE composite FKs are skipped when a column is NULL, so forbid the
    -- orphan combinations explicitly.
    CONSTRAINT ck_listing_model_needs_make CHECK (model_id IS NULL OR make_id IS NOT NULL),
    CONSTRAINT ck_listing_trim_needs_model CHECK (trim_id IS NULL OR model_id IS NOT NULL),

    CONSTRAINT ck_listing_registration CHECK (
        first_registration_year BETWEEN 1900 AND 2100
        AND (first_registration_month IS NULL OR first_registration_month BETWEEN 1 AND 12)),
    CONSTRAINT ck_listing_mileage CHECK (mileage_km BETWEEN 0 AND 3000000),
    CONSTRAINT ck_listing_latitude  CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_listing_longitude CHECK (longitude BETWEEN -180 AND 180),

    CONSTRAINT ck_listing_fuel_type CHECK (fuel_type IN
        ('PETROL', 'DIESEL', 'HYBRID_PETROL', 'HYBRID_DIESEL', 'PLUGIN_HYBRID', 'ELECTRIC',
         'LPG', 'CNG', 'HYDROGEN', 'ETHANOL', 'OTHER')),
    CONSTRAINT ck_listing_gearbox CHECK (gearbox IN ('MANUAL', 'AUTOMATIC', 'SEMI_AUTOMATIC')),
    CONSTRAINT ck_listing_body_type CHECK (body_type IS NULL OR body_type IN
        ('HATCHBACK', 'SEDAN', 'ESTATE', 'SUV', 'COUPE', 'CONVERTIBLE', 'MPV', 'PICKUP', 'VAN', 'OTHER')),
    CONSTRAINT ck_listing_drivetrain CHECK (drivetrain IS NULL OR drivetrain IN ('FWD', 'RWD', 'AWD')),
    CONSTRAINT ck_listing_condition CHECK (vehicle_condition IN ('NEW', 'USED', 'DAMAGED')),
    CONSTRAINT ck_listing_exterior_color CHECK (exterior_color IS NULL OR exterior_color IN
        ('BLACK', 'WHITE', 'GREY', 'SILVER', 'BLUE', 'RED', 'GREEN', 'BROWN', 'BEIGE',
         'YELLOW', 'ORANGE', 'PURPLE', 'GOLD', 'OTHER')),
    CONSTRAINT ck_listing_interior_material CHECK (interior_material IS NULL OR interior_material IN
        ('CLOTH', 'LEATHER', 'PART_LEATHER', 'ALCANTARA', 'VELOUR', 'OTHER')),
    CONSTRAINT ck_listing_emission_class CHECK (emission_class IS NULL OR emission_class IN
        ('EURO_1', 'EURO_2', 'EURO_3', 'EURO_4', 'EURO_5', 'EURO_6', 'EURO_6D', 'EURO_7', 'OTHER')),
    CONSTRAINT ck_listing_numbers CHECK (
        (power_hp IS NULL OR power_hp BETWEEN 1 AND 2000)
        AND (power_kw IS NULL OR power_kw BETWEEN 1 AND 1500)
        AND (engine_displacement_cc IS NULL OR engine_displacement_cc BETWEEN 50 AND 10000)
        AND (doors IS NULL OR doors BETWEEN 1 AND 7)
        AND (seats IS NULL OR seats BETWEEN 1 AND 9)
        AND (owner_count IS NULL OR owner_count BETWEEN 0 AND 50)
        AND (warranty_months IS NULL OR warranty_months BETWEEN 0 AND 120)
        AND (co2_emissions_g_km IS NULL OR co2_emissions_g_km BETWEEN 0 AND 1000)),
    CONSTRAINT ck_listing_vin CHECK (vin IS NULL OR vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    CONSTRAINT ck_listing_published CHECK (status <> 'ACTIVE' OR published_at IS NOT NULL)
);

COMMENT ON COLUMN listing.status IS
    'Seller-facing lifecycle. PENDING_REVIEW is reserved: the MVP publishes straight to ACTIVE.';
COMMENT ON COLUMN listing.moderation_status IS
    'Admin axis, independent of status. Rejection sets moderation_status=REJECTED and status=REMOVED.';
COMMENT ON COLUMN listing.make_text IS
    'Set only when the catalogue lacks the vehicle. Feeds the admin catalogue-enrichment queue.';
COMMENT ON COLUMN listing.catalog_gap_resolved_at IS
    'Stamped when an admin has mapped a free-text listing onto catalogue rows.';
COMMENT ON COLUMN listing.hide_exact_location IS
    'TRUE = the API exposes only city, postcode and the place centroid, not the seller address.';
COMMENT ON COLUMN listing.mileage_km IS 'Always kilometres. Conversion for mile markets happens in the API layer.';
COMMENT ON COLUMN listing.vin IS 'Optional, never returned by the public API. Used for duplicate detection.';

-- Search path: every public query filters on status/moderation and is deleted_at IS NULL,
-- so the hot indexes are partial.
-- Ends on id so a keyset cursor ordering by (published_at DESC, id DESC) is read
-- straight out of index order, with no sort step.
CREATE INDEX ix_listing_live_published ON listing (published_at DESC, id DESC)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
-- Two shapes for the same filter, one per supported sort: a car marketplace is
-- browsed both newest-first (the default) and cheapest-first, and a composite
-- index only avoids a sort for the column it ends on.
CREATE INDEX ix_listing_live_make_model_recent ON listing (make_id, model_id, published_at DESC)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_make_model_price ON listing (make_id, model_id, price_amount)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_price ON listing (price_currency_code, price_amount)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_year ON listing (first_registration_year)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_mileage ON listing (mileage_km)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_place ON listing (geo_place_id)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;
CREATE INDEX ix_listing_live_geo ON listing (country_code, latitude, longitude)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL;

-- Seller dashboard and the per-role active-listing quota.
CREATE INDEX ix_listing_seller_status ON listing (seller_id, status, updated_at DESC);
-- Moderation queue.
CREATE INDEX ix_listing_moderation_queue ON listing (created_at)
    WHERE moderation_status = 'NOT_REVIEWED' AND status = 'ACTIVE';
-- Catalogue-gap queue.
CREATE INDEX ix_listing_catalog_gap ON listing (created_at)
    WHERE make_text IS NOT NULL AND catalog_gap_resolved_at IS NULL;
-- Expiry sweeper.
CREATE INDEX ix_listing_expiry ON listing (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE listing_image (
    id                UUID         PRIMARY KEY,
    listing_id        UUID         NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    position          SMALLINT     NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    storage_key       VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255),
    content_type      VARCHAR(80),
    byte_size         BIGINT,
    width             INTEGER,
    height            INTEGER,
    checksum_sha256   CHAR(64),
    upload_expires_at TIMESTAMPTZ,
    uploaded_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_listing_image_status   CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT ck_listing_image_position CHECK (position BETWEEN 0 AND 49),
    CONSTRAINT ck_listing_image_type     CHECK (content_type IS NULL OR content_type IN
        ('image/jpeg', 'image/png', 'image/webp', 'image/avif')),
    CONSTRAINT ck_listing_image_ready    CHECK (status <> 'READY' OR uploaded_at IS NOT NULL)
);

COMMENT ON TABLE listing_image IS
    'Row is created with status=PENDING when a pre-signed upload URL is issued, and flipped to READY once the storage callback confirms the object.';
COMMENT ON COLUMN listing_image.storage_key IS 'Object key of the original upload. Never a full URL — the bucket/CDN host is configuration.';

CREATE UNIQUE INDEX ux_listing_image_storage_key ON listing_image (storage_key);
-- Deferred so a whole gallery can be reordered inside one transaction.
ALTER TABLE listing_image ADD CONSTRAINT ux_listing_image_position
    UNIQUE (listing_id, position) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ix_listing_image_listing ON listing_image (listing_id, position);
-- Abandoned-upload cleanup.
CREATE INDEX ix_listing_image_pending ON listing_image (upload_expires_at) WHERE status = 'PENDING';

CREATE TABLE listing_image_variant (
    id          UUID         PRIMARY KEY,
    image_id    UUID         NOT NULL REFERENCES listing_image (id) ON DELETE CASCADE,
    variant     VARCHAR(16)  NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    width       INTEGER      NOT NULL,
    height      INTEGER      NOT NULL,
    byte_size   BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_listing_image_variant CHECK (variant IN ('THUMBNAIL', 'SMALL', 'MEDIUM', 'LARGE'))
);

CREATE UNIQUE INDEX ux_listing_image_variant ON listing_image_variant (image_id, variant);
CREATE UNIQUE INDEX ux_listing_image_variant_key ON listing_image_variant (storage_key);

CREATE TABLE listing_feature (
    listing_id UUID NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    feature_id UUID NOT NULL REFERENCES vehicle_feature (id),
    PRIMARY KEY (listing_id, feature_id)
);

CREATE INDEX ix_listing_feature_feature ON listing_feature (feature_id);

CREATE TABLE moderation_action (
    id          UUID        PRIMARY KEY,
    listing_id  UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    admin_id    UUID        NOT NULL REFERENCES app_user (id),
    action      VARCHAR(16) NOT NULL,
    reason_code VARCHAR(40),
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_moderation_action CHECK (action IN ('APPROVE', 'REJECT', 'REMOVE', 'REINSTATE')),
    CONSTRAINT ck_moderation_note   CHECK (note IS NULL OR length(note) <= 2000),
    CONSTRAINT ck_moderation_reason CHECK (action NOT IN ('REJECT', 'REMOVE') OR reason_code IS NOT NULL)
);

COMMENT ON TABLE moderation_action IS 'Append-only audit trail. The current verdict lives on listing.moderation_status.';

CREATE INDEX ix_moderation_action_listing ON moderation_action (listing_id, created_at DESC);
CREATE INDEX ix_moderation_action_admin ON moderation_action (admin_id, created_at DESC);
