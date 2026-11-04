-- AutoX V23: saved searches, and the daily e-mail of what is new in them.

CREATE TABLE saved_search (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name             VARCHAR(80)  NOT NULL,
    -- The filters, in the JSON form criteria_version describes: see SavedSearchCriteriaCodec.
    criteria_version SMALLINT     NOT NULL,
    criteria         JSONB        NOT NULL,
    -- The same search as the results page's query string, for the links back to it.
    link_params      JSONB        NOT NULL,
    -- The catalogue's names when it was saved, so the page can say which one has since gone.
    make_label       VARCHAR(80),
    model_label      VARCHAR(120),
    -- Paused from the page, or by the e-mail's unsubscribe link. Null: alerts are on.
    paused_at        TIMESTAMPTZ,
    last_digest_at   TIMESTAMPTZ  NOT NULL,
    last_visited_at  TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_saved_search_name CHECK (length(trim(name)) > 0)
);

COMMENT ON COLUMN saved_search.last_digest_at IS
    'Up to when listings have been considered for an e-mail. Starts at creation, and at resuming.';
COMMENT ON COLUMN saved_search.last_visited_at IS
    'When the search was last opened from Mes recherches: what "new" is counted from there.';

CREATE INDEX ix_saved_search_user ON saved_search (user_id, created_at);
-- The daily job's list of accounts, and the dashboard's count.
CREATE INDEX ix_saved_search_active ON saved_search (user_id) WHERE paused_at IS NULL;

-- What has been sent for a search, so that a listing is never sent twice for it.
CREATE TABLE saved_search_sent (
    saved_search_id UUID        NOT NULL REFERENCES saved_search (id) ON DELETE CASCADE,
    listing_id      UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (saved_search_id, listing_id)
);

CREATE INDEX ix_saved_search_sent_listing ON saved_search_sent (listing_id);

ALTER TABLE outbox_email DROP CONSTRAINT ck_outbox_email_type;
ALTER TABLE outbox_email ADD CONSTRAINT ck_outbox_email_type
    CHECK (email_type IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'NEW_MESSAGE', 'SAVED_SEARCH_DIGEST'));
-- One digest per person per day is checked against the outbox.
CREATE INDEX ix_outbox_email_digest ON outbox_email (recipient, created_at)
    WHERE email_type = 'SAVED_SEARCH_DIGEST';
