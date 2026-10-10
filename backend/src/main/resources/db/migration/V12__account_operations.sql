-- AutoX V12 — the account operations that were missing: password reset, and a
-- user-ended session as a revocation reason distinct from a logout on that device.

-- Single-use, short-lived. Only the SHA-256 is stored, for the same reason as
-- refresh_token: a dump of this table must not be a set of working reset links.
CREATE TABLE password_reset_token (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    ip_address    VARCHAR(45),
    CONSTRAINT ck_password_reset_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE password_reset_token IS
    'One row per reset request. Rows are kept after use as an audit trail and removed by the sweeper once long expired.';
COMMENT ON COLUMN password_reset_token.used_at IS
    'Set when the token actually reset a password. Non-null means spent: presenting it again fails.';
COMMENT ON COLUMN password_reset_token.superseded_at IS
    'Set when a newer request replaced this one. Kept separate from used_at so the trail distinguishes "spent" from "abandoned".';

CREATE UNIQUE INDEX ux_password_reset_hash ON password_reset_token (token_hash);
-- The lookup that invalidates earlier requests when a new one arrives.
CREATE INDEX ix_password_reset_user_live ON password_reset_token (user_id)
    WHERE used_at IS NULL AND superseded_at IS NULL;
-- Supports the sweeper.
CREATE INDEX ix_password_reset_expiry ON password_reset_token (expires_at);

-- A session the user ended from their own session list is not a logout: the device
-- that holds it was never asked, and the trail should say which one it was.
ALTER TABLE refresh_token DROP CONSTRAINT ck_refresh_token_reason;
ALTER TABLE refresh_token ADD CONSTRAINT ck_refresh_token_reason
    CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'ROTATED', 'REUSE_DETECTED', 'PASSWORD_CHANGED',
         'ADMIN_REVOKED', 'EXPIRED', 'USER_REVOKED'));

-- Requesting a reset sends mail to an address the requester need not own, so it is
-- throttled on both the target account and the caller — reusing the counter table
-- rather than adding a second mechanism, as the public rate limit does.
-- The column was sized for 'ACCOUNT' and 'PUBLIC_READ'; 32 leaves room to name a
-- scope after what it protects instead of abbreviating.
ALTER TABLE throttle_counter ALTER COLUMN scope TYPE VARCHAR(32);
ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP'));
