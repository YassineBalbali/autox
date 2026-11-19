-- AutoX V31 — Assistant AutoX: its three limits, and how it is used, counts only.
--
-- Nothing of a conversation is stored (docs/assistant-plan.md, D4): no message, no plan, no
-- argument, no listing shown. These tables hold counts per Paris day, and their columns are
-- integers and fixed codes, so there is nowhere a message could be written.

ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope CHECK (scope IN
    ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP', 'REGISTRATION_IP',
     'MESSAGE_SEND', 'CONVERSATION_START', 'SEARCH_INTERPRET', 'EMAIL_VERIFICATION',
     'DATA_EXPORT', 'LISTING_REPORT',
     'ASSISTANT_ADDRESS', 'ASSISTANT_ANON_DAY', 'ASSISTANT_ACCOUNT_DAY'));

CREATE TABLE assistant_usage (
    day            DATE    PRIMARY KEY,
    -- Turns that reached the planner: past the switch, the conversation's length and the limits.
    turns          INTEGER NOT NULL DEFAULT 0,
    -- Of those: answered, whatever the answer said; failed (503); two invalid plans (rephrase).
    answered       INTEGER NOT NULL DEFAULT 0,
    failed         INTEGER NOT NULL DEFAULT 0,
    invalid_plans  INTEGER NOT NULL DEFAULT 0,
    -- Turns where the planner was asked a second time.
    retries        INTEGER NOT NULL DEFAULT 0,
    -- The provider's own counts. Their sum is what the daily budget is measured against.
    tokens_in      BIGINT  NOT NULL DEFAULT 0,
    tokens_out     BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT ck_assistant_usage_counts CHECK (
        turns >= 0 AND answered >= 0 AND failed >= 0 AND invalid_plans >= 0
        AND answered + failed + invalid_plans = turns
        AND retries BETWEEN 0 AND turns
        AND tokens_in >= 0 AND tokens_out >= 0)
);

-- How often each intent and each reason for no answer came up. The code is a fixed name from the
-- server's enums, held to their shape by the check: upper-case letters and underscores, short.
CREATE TABLE assistant_usage_code (
    day          DATE    NOT NULL,
    code         TEXT    NOT NULL,
    occurrences  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, code),
    CONSTRAINT ck_assistant_usage_code CHECK (code ~ '^(INTENT|REASON)_[A-Z_]{1,40}$'),
    CONSTRAINT ck_assistant_usage_code_occurrences CHECK (occurrences >= 0)
);

COMMENT ON TABLE assistant_usage IS
    'Assistant AutoX usage per Paris day: counts and tokens only, never a message.';
COMMENT ON TABLE assistant_usage_code IS
    'Assistant AutoX intents and reasons per Paris day: fixed codes and counts only.';
