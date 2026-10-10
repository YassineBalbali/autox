-- AutoX V10 — the login throttle becomes a general request throttle.
--
-- The public search and reference endpoints need per-IP rate limiting, and the
-- mechanism they need is the one already here: an atomic upsert counter with a decay
-- window and a blocked_until instant. Rather than build a second one, the table is
-- generalised and a third scope added.
--
-- Renaming it is not cosmetic. A table called login_attempt_counter holding a row per
-- anonymous search request is a trap for whoever reads it next, and the column names
-- would say "failure" about traffic that never failed.

ALTER TABLE login_attempt_counter RENAME TO throttle_counter;

ALTER TABLE throttle_counter RENAME COLUMN failure_count    TO event_count;
ALTER TABLE throttle_counter RENAME COLUMN first_failure_at TO first_event_at;
ALTER TABLE throttle_counter RENAME COLUMN last_failure_at  TO last_event_at;

ALTER INDEX ux_login_attempt_scope_key     RENAME TO ux_throttle_scope_key;
ALTER INDEX ix_login_attempt_last_failure  RENAME TO ix_throttle_last_event;

ALTER TABLE throttle_counter RENAME CONSTRAINT ck_login_attempt_count TO ck_throttle_event_count;

-- PUBLIC_READ: unauthenticated GETs on the search and reference endpoints, keyed by
-- client address. Unlike the login scopes this counts every request, not only failures.
ALTER TABLE throttle_counter DROP CONSTRAINT ck_login_attempt_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ'));

COMMENT ON TABLE throttle_counter IS
    'One row per throttled identity and scope. Created on the first counted event, deleted on success (login scopes) or by the decay sweeper.';
COMMENT ON COLUMN throttle_counter.event_count IS
    'Events inside the current decay window: consecutive failures for ACCOUNT/IP, requests for PUBLIC_READ.';
COMMENT ON COLUMN throttle_counter.scope_key IS
    'ACCOUNT: SHA-256 of the lower-cased submitted email — hashed so this table is not a second register of addresses. IP and PUBLIC_READ: the client address as text.';
COMMENT ON COLUMN throttle_counter.blocked_until IS
    'Requests in this scope are refused until this instant.';
