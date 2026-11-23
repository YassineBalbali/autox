-- AutoX V32 — Assistant AutoX: its token budget over the last 24 hours, not the Paris day.
--
-- Groq's daily quota is a rolling 24 hours: after a day's budget was spent late in the evening,
-- a budget reset at midnight let a run start again into Groq's own refusal. The budget is now
-- measured as Groq measures it, on the tokens of the last 24 hours, counted by the hour. The
-- per-day counts of V31 stay, for the usage figures. A count, nothing of a conversation.

CREATE TABLE assistant_token_hour (
    -- The hour the tokens were spent in, truncated, UTC.
    hour    TIMESTAMPTZ PRIMARY KEY,
    tokens  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_assistant_token_hour_tokens CHECK (tokens >= 0)
);
