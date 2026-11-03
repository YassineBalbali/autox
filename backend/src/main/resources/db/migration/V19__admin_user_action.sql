-- AutoX V19: what administrators do to accounts.
--
-- One row per action: who, what, on whom, why and when. Written in the same transaction as the
-- action, so neither can happen without the other. A later audit log page reads this table, and
-- a later kind of action is one more value in the check constraint.
--
-- No foreign keys, on purpose. A record of what was done to an account has to outlive the
-- account, and a demo removal deletes its accounts by id: a key would either make that removal
-- fail or, with ON DELETE SET NULL, erase who the record was about. The addresses are copied in
-- for the same reason, and because an address can change after the action.

CREATE TABLE admin_user_action (
    id             UUID         PRIMARY KEY,
    admin_id       UUID         NOT NULL,
    admin_email    VARCHAR(255) NOT NULL,
    target_user_id UUID         NOT NULL,
    target_email   VARCHAR(255) NOT NULL,
    action         VARCHAR(16)  NOT NULL,
    reason         TEXT,
    -- What changed: the status for a suspension or reactivation, the role for a role change.
    previous_value VARCHAR(32)  NOT NULL,
    new_value      VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_user_action        CHECK (action IN ('SUSPEND', 'REACTIVATE', 'CHANGE_ROLE')),
    CONSTRAINT ck_admin_user_action_reason CHECK (action <> 'SUSPEND' OR reason IS NOT NULL),
    CONSTRAINT ck_admin_user_action_length CHECK (reason IS NULL OR length(reason) <= 2000)
);

COMMENT ON TABLE admin_user_action IS 'Append-only: a trigger refuses UPDATE and DELETE. One row per administrator action on an account.';

CREATE INDEX ix_admin_user_action_target ON admin_user_action (target_user_id, created_at DESC);
CREATE INDEX ix_admin_user_action_admin ON admin_user_action (admin_id, created_at DESC);
CREATE INDEX ix_admin_user_action_created ON admin_user_action (created_at DESC);

CREATE FUNCTION admin_user_action_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_user_action is append-only';
END;
$$;

CREATE TRIGGER tr_admin_user_action_append_only BEFORE UPDATE OR DELETE ON admin_user_action
    FOR EACH ROW EXECUTE FUNCTION admin_user_action_is_append_only();
