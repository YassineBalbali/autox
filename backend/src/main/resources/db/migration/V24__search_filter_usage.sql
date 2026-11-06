-- AutoX V24 — which filters the results page is used with, counted per hour.
--
-- Names only, never values: "fuelType" is counted, "DIESEL" is not, and nothing a visitor typed is
-- kept. One row per hour and filter; '_all' counts every search, so a filter's share can be given.
-- Only a first page counts, so "Voir plus" is not a second search.

CREATE TABLE search_filter_usage (
    hour     TIMESTAMPTZ NOT NULL,
    filter   VARCHAR(32) NOT NULL,
    searches INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (hour, filter),
    CONSTRAINT ck_search_filter_usage_hour CHECK (hour = date_trunc('hour', hour)),
    CONSTRAINT ck_search_filter_usage_count CHECK (searches >= 0)
);

COMMENT ON TABLE search_filter_usage IS
    'How many public searches used each filter, per hour: filter names only, never what was searched for.';
