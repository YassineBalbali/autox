-- AutoX V27: the administration log is kept one year, then the daily retention job deletes it.
--
-- Still append-only otherwise: a row is never changed, except for the erasure V26 allows when an
-- account is deleted, and never deleted before it is a year old. The bound is here as well as in
-- DataRetentionSweeper, so that no other code path can remove a recent row.

CREATE OR REPLACE FUNCTION admin_user_action_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.created_at < now() - interval '365 days' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.id = OLD.id
       AND NEW.admin_id IS NOT DISTINCT FROM OLD.admin_id
       AND NEW.target_user_id IS NOT DISTINCT FROM OLD.target_user_id
       AND NEW.action = OLD.action
       AND NEW.previous_value IS NOT DISTINCT FROM OLD.previous_value
       AND NEW.new_value IS NOT DISTINCT FROM OLD.new_value
       AND NEW.created_at = OLD.created_at
       AND (NEW.admin_email IS NULL OR NEW.admin_email = OLD.admin_email)
       AND (NEW.target_email IS NULL OR NEW.target_email = OLD.target_email)
       AND (NEW.reason IS NULL OR NEW.reason = OLD.reason) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'admin_user_action is append-only';
END;
$$;

COMMENT ON TABLE admin_user_action IS
    'Append-only: a trigger refuses UPDATE and DELETE, except erasure on account deletion and deletion after a year. One row per administrator action on an account.';
