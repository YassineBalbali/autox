-- AutoX V11 — price history.
--
-- Until now price was mutated in place, so the only record of what a vehicle was asked
-- for was whatever the row said today. That is the primary input to price estimation,
-- and it is the one gap that cannot be filled retroactively: a price nobody recorded is
-- gone. Added before the frontend exists, so the series starts at the first real listing.
--
-- Shape: one row per price the public saw, from created_at onward. There is no
-- previous_amount column — the preceding row is the previous price, and a denormalised
-- copy is one more thing that can disagree with it. The delta is a window function away.

CREATE TABLE listing_price_history (
    id                  UUID        PRIMARY KEY,
    listing_id          UUID        NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
    price_amount        BIGINT      NOT NULL,
    price_currency_code VARCHAR(3)  NOT NULL REFERENCES currency (code),
    reason              VARCHAR(16) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_listing_price_history_amount CHECK (price_amount >= 0),
    CONSTRAINT ck_listing_price_history_reason CHECK (reason IN ('INITIAL', 'SELLER_EDIT'))
);

COMMENT ON TABLE listing_price_history IS
    'Append-only. One row per asking price the public saw: INITIAL at first publication, SELLER_EDIT on every later change. Draft edits before first publication are not history, they are composition.';
COMMENT ON COLUMN listing_price_history.price_amount IS
    'Minor units of price_currency_code, as on listing.price_amount. Never a floating-point type.';
COMMENT ON COLUMN listing_price_history.price_currency_code IS
    'Carried per row because a currency change is a price change: the amount alone is meaningless without it.';

-- The access pattern is "the series for one listing, in order", which is also what a
-- later export for model training wants.
CREATE INDEX ix_listing_price_history_listing ON listing_price_history (listing_id, created_at);
