-- AutoX V15 — messages between a buyer and a seller, about one listing.
--
-- A buyer reaches a seller only through the platform: the seller's email and phone are
-- never in any response, and every message is stored here. One conversation per listing
-- and buyer; the buyer starts it, both sides write in it.
--
-- A sent message is never edited or deleted by its author. A recipient can report it, and
-- a moderator can hide it — hidden, not deleted, so the record of what was said survives.

CREATE TABLE conversation (
    id                  UUID        PRIMARY KEY,
    -- Cascades because a listing row is only ever hard-deleted wholesale (the demo seed's
    -- removal); a seller's delete is a soft delete, and the conversation outlives it.
    listing_id          UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    buyer_id            UUID        NOT NULL REFERENCES app_user (id),
    -- The listing's seller, copied: a listing never changes hands, and the inbox query
    -- for a seller is then one index range instead of a join through every listing.
    seller_id           UUID        NOT NULL REFERENCES app_user (id),
    created_at          TIMESTAMPTZ NOT NULL,
    last_message_at     TIMESTAMPTZ NOT NULL,
    -- Read up to here. A message from the other side created after it is unread.
    buyer_read_at       TIMESTAMPTZ,
    seller_read_at      TIMESTAMPTZ,

    CONSTRAINT ux_conversation_listing_buyer UNIQUE (listing_id, buyer_id),
    CONSTRAINT ck_conversation_not_self CHECK (buyer_id <> seller_id)
);

CREATE INDEX ix_conversation_buyer  ON conversation (buyer_id, last_message_at DESC);
CREATE INDEX ix_conversation_seller ON conversation (seller_id, last_message_at DESC);

COMMENT ON TABLE conversation IS
    'One per listing and buyer. Started by the buyer; the seller is the listing''s.';

CREATE TABLE message (
    id               UUID        PRIMARY KEY,
    conversation_id  UUID        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    sender_id        UUID        NOT NULL REFERENCES app_user (id),
    body             TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    -- Set by a moderator. The body is kept; neither side is shown it any more.
    hidden_at        TIMESTAMPTZ,
    hidden_by        UUID        REFERENCES app_user (id),

    CONSTRAINT ck_message_body CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT ck_message_hidden CHECK ((hidden_at IS NULL) = (hidden_by IS NULL))
);

CREATE INDEX ix_message_conversation ON message (conversation_id, created_at);

-- No API path edits or deletes a message, and this makes it a property of the data rather
-- than of the code: what was sent, by whom and when cannot be changed by any statement.
-- Only the moderation columns may be set. Deleting the conversation (a listing's hard
-- delete) still removes its messages, which is the one deletion there is.
CREATE FUNCTION message_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.body IS DISTINCT FROM OLD.body
       OR NEW.sender_id IS DISTINCT FROM OLD.sender_id
       OR NEW.conversation_id IS DISTINCT FROM OLD.conversation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a sent message cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_message_immutable BEFORE UPDATE ON message
    FOR EACH ROW EXECUTE FUNCTION message_is_immutable();

CREATE TABLE message_report (
    id           UUID        PRIMARY KEY,
    message_id   UUID        NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    reporter_id  UUID        NOT NULL REFERENCES app_user (id),
    reason       VARCHAR(24) NOT NULL,
    note         VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL,
    -- How a moderator closed it: the message hidden, or the report dismissed.
    resolution   VARCHAR(16),
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID        REFERENCES app_user (id),

    CONSTRAINT ux_message_report_once UNIQUE (message_id, reporter_id),
    CONSTRAINT ck_message_report_reason CHECK (reason IN ('SCAM', 'ABUSIVE', 'SPAM', 'OTHER')),
    CONSTRAINT ck_message_report_resolution CHECK (resolution IS NULL OR resolution IN ('HIDDEN', 'DISMISSED')),
    CONSTRAINT ck_message_report_resolved CHECK (
        (resolution IS NULL AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (resolution IS NOT NULL AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
);

-- The moderators' queue: open reports, oldest first.
CREATE INDEX ix_message_report_open ON message_report (created_at) WHERE resolution IS NULL;

-- Two throttle scopes, both keyed by the sender's user id rather than an address: sending
-- needs an account, and the account is what a spammer has to spend.
--   MESSAGE_SEND:       every message sent.
--   CONVERSATION_START: new conversations only, which is what a spammer writing to every
--                       seller has to do, and what a genuine buyer does a few times a day.
ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP',
                     'REGISTRATION_IP', 'MESSAGE_SEND', 'CONVERSATION_START'));
