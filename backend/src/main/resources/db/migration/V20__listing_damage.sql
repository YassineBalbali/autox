-- AutoX V20: a damaged vehicle is declared, with what happened to it.
--
-- Until now "damaged" was a third value of vehicle_condition, with nothing attached. It becomes
-- a declaration of its own, and the condition goes back to meaning new or used. A damaged car can
-- be either, and one question in two places could only ever disagree.
--
-- A listing saved as DAMAGED before this keeps being damaged. Its seller never said how, so the
-- type is "other", the description says that nothing was stated, and whether it runs stays
-- unknown: that column may be null for these listings alone, because the API requires it from
-- here on. Saying "non roulant" on a seller's behalf would be inventing a fact about their car.

ALTER TABLE listing
    ADD COLUMN damaged            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN damage_types       TEXT[],
    ADD COLUMN damage_running     BOOLEAN,
    ADD COLUMN damage_description TEXT;

UPDATE listing
   SET damaged = TRUE,
       damage_types = ARRAY['OTHER'],
       damage_description = 'Non précisé par le vendeur.',
       vehicle_condition = 'USED'
 WHERE vehicle_condition = 'DAMAGED';

ALTER TABLE listing DROP CONSTRAINT ck_listing_condition;
ALTER TABLE listing ADD CONSTRAINT ck_listing_condition CHECK (vehicle_condition IN ('NEW', 'USED'));

-- IS NOT NULL spelled out: a check passes on NULL, so without it a damaged row with no details would.
ALTER TABLE listing ADD CONSTRAINT ck_listing_damage CHECK (
    CASE WHEN damaged
         THEN damage_types IS NOT NULL
              AND cardinality(damage_types) >= 1
              AND damage_types <@ ARRAY['BODYWORK', 'MECHANICAL', 'HAIL', 'FLOOD', 'OTHER']
              AND damage_description IS NOT NULL
              AND length(btrim(damage_description)) BETWEEN 1 AND 2000
         ELSE damage_types IS NULL AND damage_running IS NULL AND damage_description IS NULL
    END);

COMMENT ON COLUMN listing.damaged IS
    'Declared by the seller. Out of search by default, and never a comparable or given a price verdict.';
COMMENT ON COLUMN listing.damage_running IS
    'Whether it runs. Required by the API; null only on listings migrated from the old condition.';
