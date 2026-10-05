-- AutoX V2 — accounts, professional seller profiles, refresh-token store.

CREATE TABLE app_user (
    id                      UUID         PRIMARY KEY,
    email                   VARCHAR(255) NOT NULL,
    password_hash           VARCHAR(100) NOT NULL,
    role                    VARCHAR(16)  NOT NULL,
    status                  VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    display_name            VARCHAR(120) NOT NULL,
    first_name              VARCHAR(80),
    last_name               VARCHAR(80),
    phone_e164              VARCHAR(20),
    phone_verified_at       TIMESTAMPTZ,
    country_code            VARCHAR(2)      NOT NULL REFERENCES country (code),
    locale                  VARCHAR(10)  NOT NULL,
    preferred_currency_code VARCHAR(3)      REFERENCES currency (code),
    active_listing_limit    SMALLINT,
    email_verified_at       TIMESTAMPTZ,
    last_login_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_role   CHECK (role IN ('PARTICULIER', 'PROFESSIONNEL', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_app_user_phone  CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{6,18}$'),
    CONSTRAINT ck_app_user_limit  CHECK (active_listing_limit IS NULL OR active_listing_limit >= 0)
);

COMMENT ON COLUMN app_user.active_listing_limit IS
    'Per-account override. NULL = apply the role default from application config.';
COMMENT ON COLUMN app_user.locale IS 'BCP 47 tag, e.g. fr-FR. Drives API response language and formatting.';

-- Case-insensitive uniqueness without the citext extension.
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));
CREATE INDEX ix_app_user_role_status ON app_user (role, status);
CREATE INDEX ix_app_user_country ON app_user (country_code);

CREATE TABLE pro_profile (
    user_id             UUID         PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    company_name        VARCHAR(160) NOT NULL,
    legal_form          VARCHAR(60),
    registration_number VARCHAR(40),
    vat_number          VARCHAR(40),
    website_url         VARCHAR(255),
    description         TEXT,
    logo_storage_key    VARCHAR(512),
    address_line1       VARCHAR(160),
    address_line2       VARCHAR(160),
    geo_place_id        UUID         REFERENCES geo_place (id),
    contact_phone_e164  VARCHAR(20),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_pro_profile_description CHECK (description IS NULL OR length(description) <= 5000)
);

COMMENT ON TABLE pro_profile IS
    'One row per PROFESSIONNEL account. Subscription tiers are out of MVP scope and will attach to this table later.';
COMMENT ON COLUMN pro_profile.registration_number IS 'FR: SIRET. DE: Handelsregisternummer. Country-specific, hence free text.';

CREATE INDEX ix_pro_profile_geo_place ON pro_profile (geo_place_id);

-- Long-lived refresh tokens (mobile sessions persist for months).
-- Only a hash is stored; the token itself is never persisted.
CREATE TABLE refresh_token (
    id             UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash     VARCHAR(64)    NOT NULL,
    family_id      UUID        NOT NULL,
    device_label   VARCHAR(120),
    user_agent     VARCHAR(512),
    ip_address     VARCHAR(45),
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    last_used_at   TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(40),
    CONSTRAINT ck_refresh_token_hash   CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_token_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'ROTATED', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'ADMIN_REVOKED', 'EXPIRED'))
);

COMMENT ON COLUMN refresh_token.token_hash IS 'SHA-256 of the opaque refresh token, lower-case hex.';
COMMENT ON COLUMN refresh_token.family_id IS
    'Rotation lineage. Replaying a token that was already rotated revokes the whole family.';

CREATE UNIQUE INDEX ux_refresh_token_hash ON refresh_token (token_hash);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id, expires_at DESC);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
-- Supports the expired-token cleanup job.
CREATE INDEX ix_refresh_token_live_expiry ON refresh_token (expires_at) WHERE revoked_at IS NULL;
