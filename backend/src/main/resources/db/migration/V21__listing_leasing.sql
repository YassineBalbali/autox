-- AutoX V21: a listing is a sale or a leasing offer.
--
-- A leasing offer keeps price_amount, as the vehicle's cash price: the offer's advertising has to
-- state it (docs/leasing.md), and every other column here is about the lease.
--
-- The total cost is stored because search and display read it, and the check below holds it to its
-- definition: nothing can write a total that does not add up, whoever is writing.

ALTER TABLE listing
    ADD COLUMN offer_type               VARCHAR(8) NOT NULL DEFAULT 'SALE',
    ADD COLUMN leasing_type             VARCHAR(8),
    ADD COLUMN leasing_monthly          BIGINT,
    ADD COLUMN leasing_first_payment    BIGINT,
    ADD COLUMN leasing_duration_months  INTEGER,
    ADD COLUMN leasing_mileage_per_year INTEGER,
    ADD COLUMN leasing_option_price     BIGINT,
    ADD COLUMN leasing_total_cost       BIGINT;

ALTER TABLE listing ADD CONSTRAINT ck_listing_offer_type CHECK (offer_type IN ('SALE', 'LEASING'));

-- IS NOT NULL spelled out, and COALESCE around the sum: a check passes on NULL.
ALTER TABLE listing ADD CONSTRAINT ck_listing_leasing CHECK (
    CASE WHEN offer_type = 'LEASING'
         THEN leasing_type IS NOT NULL
              AND leasing_type IN ('LOA', 'LLD')
              AND leasing_monthly IS NOT NULL AND leasing_monthly > 0
              AND leasing_first_payment IS NOT NULL AND leasing_first_payment >= 0
              AND leasing_duration_months IS NOT NULL AND leasing_duration_months BETWEEN 6 AND 120
              AND leasing_mileage_per_year IS NOT NULL AND leasing_mileage_per_year > 0
              AND (CASE WHEN leasing_type = 'LOA'
                        THEN leasing_option_price IS NOT NULL AND leasing_option_price >= 0
                        ELSE leasing_option_price IS NULL END)
              AND leasing_total_cost IS NOT NULL
              AND leasing_total_cost = leasing_first_payment
                                       + leasing_monthly * (leasing_duration_months - 1)
                                       + COALESCE(leasing_option_price, 0)
         ELSE leasing_type IS NULL AND leasing_monthly IS NULL AND leasing_first_payment IS NULL
              AND leasing_duration_months IS NULL AND leasing_mileage_per_year IS NULL
              AND leasing_option_price IS NULL AND leasing_total_cost IS NULL
    END);

-- A damaged car is sold as it stands; nobody leases one.
ALTER TABLE listing ADD CONSTRAINT ck_listing_leasing_not_damaged CHECK (offer_type = 'SALE' OR NOT damaged);

COMMENT ON COLUMN listing.offer_type IS
    'Sale or leasing. The two never appear in the same search, and leasing is never a comparable.';
COMMENT ON COLUMN listing.leasing_total_cost IS
    'First payment + monthly x (duration - 1), plus the option price for LOA. Computed by the server.';

-- The leasing results' default sort.
CREATE INDEX ix_listing_live_leasing_monthly ON listing (leasing_monthly, id)
    WHERE status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL AND offer_type = 'LEASING';
