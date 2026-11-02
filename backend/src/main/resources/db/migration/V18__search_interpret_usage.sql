-- AutoX V18 — how natural-language search is used, one row a day, counts only.
--
-- Never the text of a query: what a visitor typed is theirs, and the admin dashboard needs only
-- how often the feature is used, how often it fails and how often it cannot do everything it
-- was asked. Days are Paris days, the site's.

CREATE TABLE search_interpret_usage (
    day                  DATE    PRIMARY KEY,
    -- Requests that reached the provider: past the feature check, validation and rate limit.
    requests             INTEGER NOT NULL DEFAULT 0,
    -- Of those, the ones answered 503: timeout, spent quota, provider error.
    failed               INTEGER NOT NULL DEFAULT 0,
    -- Of the answered ones, those with at least one part not understood.
    with_not_understood  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_search_interpret_usage_counts CHECK (
        requests >= 0 AND failed BETWEEN 0 AND requests
        AND with_not_understood BETWEEN 0 AND requests - failed)
);

COMMENT ON TABLE search_interpret_usage IS
    'Natural-language search usage per Paris day: counts only, never query text.';
