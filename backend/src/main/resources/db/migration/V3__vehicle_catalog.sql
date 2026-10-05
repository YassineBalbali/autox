-- AutoX V3 — vehicle reference catalogue (make / model / trim) and the equipment vocabulary.
-- Catalogue rows are platform-owned data, never written by sellers.

CREATE TABLE vehicle_make (
    id               UUID         PRIMARY KEY,
    slug             VARCHAR(60)  NOT NULL,
    name             VARCHAR(80)  NOT NULL,
    logo_storage_key VARCHAR(512),
    popular          BOOLEAN      NOT NULL DEFAULT FALSE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_make_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

COMMENT ON COLUMN vehicle_make.popular IS 'Pins the make to the top of the search dropdown. Curated, per-platform not per-country.';

CREATE UNIQUE INDEX ux_vehicle_make_slug ON vehicle_make (slug);
CREATE INDEX ix_vehicle_make_active_name ON vehicle_make (active, name);

CREATE TABLE vehicle_model (
    id         UUID        PRIMARY KEY,
    make_id    UUID        NOT NULL REFERENCES vehicle_make (id),
    slug       VARCHAR(80) NOT NULL,
    name       VARCHAR(120) NOT NULL,
    year_from  SMALLINT,
    year_to    SMALLINT,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_model_slug  CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_vehicle_model_years CHECK (
        (year_from IS NULL OR year_from BETWEEN 1900 AND 2100)
        AND (year_to IS NULL OR year_to BETWEEN 1900 AND 2100)
        AND (year_from IS NULL OR year_to IS NULL OR year_to >= year_from))
);

CREATE UNIQUE INDEX ux_vehicle_model_make_slug ON vehicle_model (make_id, slug);
CREATE INDEX ix_vehicle_model_make_name ON vehicle_model (make_id, name);

-- Trim = a concrete factory variant ("320d xDrive M Sport"). Optional on a listing.
CREATE TABLE vehicle_trim (
    id                     UUID         PRIMARY KEY,
    model_id               UUID         NOT NULL REFERENCES vehicle_model (id),
    slug                   VARCHAR(120) NOT NULL,
    name                   VARCHAR(160) NOT NULL,
    year_from              SMALLINT,
    year_to                SMALLINT,
    body_type              VARCHAR(24),
    fuel_type              VARCHAR(24),
    gearbox                VARCHAR(24),
    drivetrain             VARCHAR(8),
    power_hp               SMALLINT,
    power_kw               SMALLINT,
    engine_displacement_cc INTEGER,
    doors                  SMALLINT,
    seats                  SMALLINT,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vehicle_trim_slug  CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_vehicle_trim_years CHECK (
        (year_from IS NULL OR year_from BETWEEN 1900 AND 2100)
        AND (year_to IS NULL OR year_to BETWEEN 1900 AND 2100)
        AND (year_from IS NULL OR year_to IS NULL OR year_to >= year_from)),
    CONSTRAINT ck_vehicle_trim_body_type CHECK (body_type IS NULL OR body_type IN
        ('HATCHBACK', 'SEDAN', 'ESTATE', 'SUV', 'COUPE', 'CONVERTIBLE', 'MPV', 'PICKUP', 'VAN', 'OTHER')),
    CONSTRAINT ck_vehicle_trim_fuel_type CHECK (fuel_type IS NULL OR fuel_type IN
        ('PETROL', 'DIESEL', 'HYBRID_PETROL', 'HYBRID_DIESEL', 'PLUGIN_HYBRID', 'ELECTRIC',
         'LPG', 'CNG', 'HYDROGEN', 'ETHANOL', 'OTHER')),
    CONSTRAINT ck_vehicle_trim_gearbox CHECK (gearbox IS NULL OR gearbox IN
        ('MANUAL', 'AUTOMATIC', 'SEMI_AUTOMATIC')),
    CONSTRAINT ck_vehicle_trim_drivetrain CHECK (drivetrain IS NULL OR drivetrain IN ('FWD', 'RWD', 'AWD')),
    CONSTRAINT ck_vehicle_trim_numbers CHECK (
        (power_hp IS NULL OR power_hp BETWEEN 1 AND 2000)
        AND (power_kw IS NULL OR power_kw BETWEEN 1 AND 1500)
        AND (engine_displacement_cc IS NULL OR engine_displacement_cc BETWEEN 50 AND 10000)
        AND (doors IS NULL OR doors BETWEEN 1 AND 7)
        AND (seats IS NULL OR seats BETWEEN 1 AND 9))
);

CREATE UNIQUE INDEX ux_vehicle_trim_model_slug ON vehicle_trim (model_id, slug);
CREATE INDEX ix_vehicle_trim_model_name ON vehicle_trim (model_id, name);

-- Equipment vocabulary. Codes are stable and language-neutral; labels are translated.
CREATE TABLE vehicle_feature (
    id            UUID        PRIMARY KEY,
    code          VARCHAR(60) NOT NULL,
    category      VARCHAR(24) NOT NULL,
    display_order SMALLINT    NOT NULL DEFAULT 0,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_vehicle_feature_code     CHECK (code ~ '^[A-Z0-9]+(_[A-Z0-9]+)*$'),
    CONSTRAINT ck_vehicle_feature_category CHECK (category IN
        ('COMFORT', 'SAFETY', 'MULTIMEDIA', 'ASSISTANCE', 'EXTERIOR', 'INTERIOR', 'OTHER'))
);

CREATE UNIQUE INDEX ux_vehicle_feature_code ON vehicle_feature (code);

CREATE TABLE vehicle_feature_translation (
    feature_id UUID         NOT NULL REFERENCES vehicle_feature (id) ON DELETE CASCADE,
    locale     VARCHAR(10)  NOT NULL,
    label      VARCHAR(160) NOT NULL,
    PRIMARY KEY (feature_id, locale)
);

COMMENT ON TABLE vehicle_feature_translation IS
    'Feature labels per locale. Enum-style vehicle attributes (fuel, gearbox, body) are instead translated in the API message bundles.';
