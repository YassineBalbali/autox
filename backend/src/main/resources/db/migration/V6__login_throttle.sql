-- AutoX V6 — login failure counters backing progressive throttling.
-- State lives in PostgreSQL (no Redis yet), so it survives a restart and is shared
-- across instances.

CREATE TABLE login_attempt_counter (
    id               UUID         PRIMARY KEY,
    scope            VARCHAR(16)  NOT NULL,
    scope_key        VARCHAR(255) NOT NULL,
    failure_count    INTEGER      NOT NULL DEFAULT 0,
    first_failure_at TIMESTAMPTZ  NOT NULL,
    last_failure_at  TIMESTAMPTZ  NOT NULL,
    blocked_until    TIMESTAMPTZ,
    CONSTRAINT ck_login_attempt_scope CHECK (scope IN ('ACCOUNT', 'IP')),
    CONSTRAINT ck_login_attempt_count CHECK (failure_count >= 0)
);

COMMENT ON TABLE login_attempt_counter IS
    'One row per throttled identity. Rows are created on the first failure and deleted on success or by the decay sweeper.';
COMMENT ON COLUMN login_attempt_counter.scope_key IS
    'ACCOUNT: SHA-256 of the lower-cased submitted email — hashed so this table is not a second register of addresses, and because it is only ever looked up, never read back. IP: the client address as text.';
COMMENT ON COLUMN login_attempt_counter.blocked_until IS
    'Attempts are refused until this instant. Grows with each failure up to a cap, so a real user is never locked out for long.';

CREATE UNIQUE INDEX ux_login_attempt_scope_key ON login_attempt_counter (scope, scope_key);
-- Supports the decay sweeper.
CREATE INDEX ix_login_attempt_last_failure ON login_attempt_counter (last_failure_at);
