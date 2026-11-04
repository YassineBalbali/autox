-- AutoX V22: transactional email through an outbox, and verifying an address.
--
-- outbox_email holds what is to be sent. A row is written in the same transaction as the change
-- it reports, and a poller sends it afterwards, so a request never waits on the mail provider and
-- mail is never sent for something that rolled back.
--
-- No foreign keys, as on admin_user_action: the throttle and the dashboard read these rows after
-- a conversation or an account may have gone, and the address is copied in for the same reason.

CREATE TABLE outbox_email (
    id              UUID         PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    body_text       TEXT,
    body_html       TEXT,
    email_type      VARCHAR(24)  NOT NULL,
    status          VARCHAR(8)   NOT NULL DEFAULT 'PENDING',
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    conversation_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_email_type   CHECK (email_type IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'NEW_MESSAGE')),
    CONSTRAINT ck_outbox_email_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbox_email_body   CHECK (status <> 'PENDING' OR (body_text IS NOT NULL AND body_html IS NOT NULL)),
    CONSTRAINT ck_outbox_email_sent   CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

COMMENT ON TABLE outbox_email IS
    'Mail to send, and what became of it. Written with the business change; sent by OutboxPoller.';
COMMENT ON COLUMN outbox_email.body_text IS
    'Null once the row is SENT or FAILED. A body can hold a reset or verification link, which is a credential: it stays here only as long as it may still be sent.';
COMMENT ON COLUMN outbox_email.attempts IS
    'Counted when a row is claimed, so an instance that dies mid-send still spends an attempt.';
COMMENT ON COLUMN outbox_email.next_attempt_at IS
    'When the row is next due. A claim moves it forward as a lease; a failure moves it to the backoff.';
COMMENT ON COLUMN outbox_email.conversation_id IS
    'NEW_MESSAGE only: what the notification throttle looks up.';

-- The claim: due PENDING rows, oldest due first.
CREATE INDEX ix_outbox_email_due ON outbox_email (next_attempt_at, id) WHERE status = 'PENDING';
-- The notification throttle.
CREATE INDEX ix_outbox_email_notification ON outbox_email (conversation_id, recipient, created_at)
    WHERE conversation_id IS NOT NULL;
-- The dashboard's counts and the sweeper.
CREATE INDEX ix_outbox_email_created ON outbox_email (created_at);

-- A single-use verification link, stored hashed like a password reset token.
CREATE TABLE email_verification_token (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    CONSTRAINT ck_email_verification_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX ux_email_verification_hash ON email_verification_token (token_hash);
CREATE INDEX ix_email_verification_user_live ON email_verification_token (user_id)
    WHERE used_at IS NULL AND superseded_at IS NULL;
CREATE INDEX ix_email_verification_expiry ON email_verification_token (expires_at);

-- Who is held back until their address is verified. Set on sign-up from now on. False for every
-- account that exists today, and for what the demo seed creates: they keep working as they do,
-- and status cannot tell them apart, since every one of them reads PENDING_VERIFICATION too.
ALTER TABLE app_user ADD COLUMN email_verification_required BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN app_user.email_verification_required IS
    'Until email_verified_at is set, this account cannot publish a listing or send a message.';

-- "Renvoyer le lien", per account.
ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP',
                     'REGISTRATION_IP', 'MESSAGE_SEND', 'CONVERSATION_START', 'SEARCH_INTERPRET',
                     'EMAIL_VERIFICATION'));
