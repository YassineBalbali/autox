-- Account deletion, data retention and listing reports.
--
-- A deleted account becomes a tombstone: its row stays, status DELETED, with no personal data
-- left in it (UserDeletionService). Conversations, moderation history and the administration
-- log keep pointing at it, and show it as "Compte supprimé". Its listings are deleted, and so,
-- after a while, are listings their seller deleted (DataRetentionSweeper). What referred to a
-- listing and has to outlive it is detached from it instead of deleted with it.

-- ---- a conversation outlives its listing -------------------------------------------------
-- The other party keeps the conversation when a seller deletes their account or a deleted
-- listing is purged. It was deleted with the listing.
ALTER TABLE conversation ALTER COLUMN listing_id DROP NOT NULL;
ALTER TABLE conversation DROP CONSTRAINT conversation_listing_id_fkey;
ALTER TABLE conversation ADD CONSTRAINT conversation_listing_id_fkey
    FOREIGN KEY (listing_id) REFERENCES listing (id) ON DELETE SET NULL;

-- ---- so does the moderation history ------------------------------------------------------
-- A decision is kept, with its reason code and date, when the listing goes. Its free-text note
-- is cleared then, since it may describe the seller.
ALTER TABLE moderation_action ALTER COLUMN listing_id DROP NOT NULL;
ALTER TABLE moderation_action DROP CONSTRAINT moderation_action_listing_id_fkey;
ALTER TABLE moderation_action ADD CONSTRAINT moderation_action_listing_id_fkey
    FOREIGN KEY (listing_id) REFERENCES listing (id) ON DELETE SET NULL;

-- ---- erased messages ----------------------------------------------------------------------
-- The text of a deleted account's messages is erased; the message stays, so the other party's
-- side of the conversation still reads in order. Only a deleted author's message may be empty.
ALTER TABLE message ALTER COLUMN body DROP NOT NULL;
ALTER TABLE message ADD COLUMN erased_at TIMESTAMPTZ;
ALTER TABLE message ADD CONSTRAINT ck_message_erased CHECK ((body IS NULL) = (erased_at IS NOT NULL));

-- A sent message still cannot be changed, with one exception: its text erased, once and for good.
CREATE OR REPLACE FUNCTION message_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sender_id IS DISTINCT FROM OLD.sender_id
       OR NEW.conversation_id IS DISTINCT FROM OLD.conversation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (NEW.body IS DISTINCT FROM OLD.body AND NOT (NEW.body IS NULL AND NEW.erased_at IS NOT NULL))
       OR (OLD.erased_at IS NOT NULL AND NEW.erased_at IS DISTINCT FROM OLD.erased_at) THEN
        RAISE EXCEPTION 'a sent message cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

-- ---- the administration log keeps no personal data of a deleted account -------------------
-- The addresses are cleared, and a suspension's reason with them.
ALTER TABLE admin_user_action ALTER COLUMN admin_email DROP NOT NULL;
ALTER TABLE admin_user_action ALTER COLUMN target_email DROP NOT NULL;
ALTER TABLE admin_user_action DROP CONSTRAINT ck_admin_user_action_reason;
ALTER TABLE admin_user_action ADD CONSTRAINT ck_admin_user_action_reason
    CHECK (action <> 'SUSPEND' OR reason IS NOT NULL OR target_email IS NULL);

-- Still append-only, with one exception: the addresses and the reason cleared, never set or changed.
CREATE OR REPLACE FUNCTION admin_user_action_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
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

-- ---- new outbox type and throttle scopes --------------------------------------------------
ALTER TABLE outbox_email DROP CONSTRAINT ck_outbox_email_type;
ALTER TABLE outbox_email ADD CONSTRAINT ck_outbox_email_type CHECK (email_type IN
    ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'NEW_MESSAGE', 'SAVED_SEARCH_DIGEST', 'ACCOUNT_DELETED'));

ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope CHECK (scope IN
    ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP', 'REGISTRATION_IP',
     'MESSAGE_SEND', 'CONVERSATION_START', 'SEARCH_INTERPRET', 'EMAIL_VERIFICATION',
     'DATA_EXPORT', 'LISTING_REPORT'));

-- ---- listing reports ------------------------------------------------------------------------
-- One per person and listing. Deleted with the listing: a report on something no longer there
-- has nothing left to decide.
CREATE TABLE listing_report (
    id           UUID         PRIMARY KEY,
    listing_id   UUID         NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    reporter_id  UUID         NOT NULL REFERENCES app_user (id),
    reason       VARCHAR(20)  NOT NULL,
    note         VARCHAR(1000),
    created_at   TIMESTAMPTZ  NOT NULL,
    resolution   VARCHAR(20),
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID         REFERENCES app_user (id),
    CONSTRAINT ux_listing_report_once UNIQUE (listing_id, reporter_id),
    CONSTRAINT ck_listing_report_reason CHECK (reason IN ('SCAM', 'MISLEADING', 'INAPPROPRIATE', 'OTHER')),
    CONSTRAINT ck_listing_report_resolution CHECK (resolution IS NULL OR resolution IN ('DISMISSED', 'REMOVED')),
    CONSTRAINT ck_listing_report_resolved CHECK ((resolution IS NULL) = (resolved_at IS NULL))
);

-- The moderators' queue: open reports, oldest first.
CREATE INDEX ix_listing_report_open ON listing_report (created_at, id) WHERE resolution IS NULL;
CREATE INDEX ix_listing_report_listing ON listing_report (listing_id) WHERE resolution IS NULL;

-- ---- retention ------------------------------------------------------------------------------
-- The daily sweep finds deleted listings by when they were deleted, and old conversations by
-- their last message.
CREATE INDEX ix_listing_deleted_at ON listing (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_conversation_last_message ON conversation (last_message_at);
