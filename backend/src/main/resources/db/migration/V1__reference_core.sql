-- AutoX V1 — platform reference data: currencies, countries, geographic places.
-- Multi-country from day one: every monetary and located row points back here.

CREATE TABLE currency (
    code        CHAR(3)     PRIMARY KEY,
    minor_unit  SMALLINT    NOT NULL DEFAULT 2,
    symbol      VARCHAR(8)  NOT NULL,
    CONSTRAINT ck_currency_code      CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currency_minor_unit CHECK (minor_unit BETWEEN 0 AND 4)
);

COMMENT ON COLUMN currency.minor_unit IS
    'Decimal digits in the minor unit. All amounts in this schema are stored as integers of the minor unit (EUR cents).';

CREATE TABLE country (
    code                  CHAR(2)      PRIMARY KEY,
    name                  VARCHAR(80)  NOT NULL,
    default_currency_code CHAR(3)      NOT NULL REFERENCES currency (code),
    default_locale        VARCHAR(10)  NOT NULL,
    phone_prefix          VARCHAR(8)   NOT NULL,
    distance_unit         VARCHAR(8)   NOT NULL DEFAULT 'KM',
    enabled               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_country_code          CHECK (code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_country_distance_unit CHECK (distance_unit IN ('KM', 'MILE'))
);

COMMENT ON COLUMN country.enabled IS
    'FALSE = known country, not yet open for listings. Lets the catalog be seeded ahead of a market launch.';
COMMENT ON COLUMN country.distance_unit IS
    'Display unit only. Mileage is always persisted in kilometres.';

-- Postal-code level gazetteer, seeded per country. Powers region facets and
-- radius search ("within 50 km of Paris") without a PostGIS dependency.
CREATE TABLE geo_place (
    id              UUID          PRIMARY KEY,
    country_code    CHAR(2)       NOT NULL REFERENCES country (code),
    postal_code     VARCHAR(12)   NOT NULL,
    city            VARCHAR(120)  NOT NULL,
    city_normalized VARCHAR(120)  NOT NULL,
    admin1_code     VARCHAR(12),
    admin1_name     VARCHAR(120),
    admin2_code     VARCHAR(12),
    admin2_name     VARCHAR(120),
    latitude        NUMERIC(9, 6) NOT NULL,
    longitude       NUMERIC(9, 6) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_geo_place_latitude  CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_geo_place_longitude CHECK (longitude BETWEEN -180 AND 180)
);

COMMENT ON COLUMN geo_place.city_normalized IS
    'Lower-cased, accent- and punctuation-stripped city name. Written by the backend; used for lookup and uniqueness.';
COMMENT ON COLUMN geo_place.admin1_name IS 'FR: region. DE: Bundesland.';
COMMENT ON COLUMN geo_place.admin2_name IS 'FR: departement. DE: Kreis.';

CREATE UNIQUE INDEX ux_geo_place_country_postal_city
    ON geo_place (country_code, postal_code, city_normalized);
CREATE INDEX ix_geo_place_country_city
    ON geo_place (country_code, city_normalized);
CREATE INDEX ix_geo_place_country_postal
    ON geo_place (country_code, postal_code);
-- Bounding-box prefilter for radius search; exact haversine is applied after.
CREATE INDEX ix_geo_place_lat_lon
    ON geo_place (latitude, longitude);
