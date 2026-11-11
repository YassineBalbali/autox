-- AutoX V30: the Crit'Air class of a listing, estimated from its fuel and first registration.
--
-- The table is the official one for passenger cars (M1): arrêté du 21 juin 2016 établissant la
-- nomenclature des véhicules classés en fonction de leur niveau d'émission de polluants
-- atmosphériques (JORF n° 0145 du 23 juin 2016, NOR DEVR1608006A), annexe I, as summarised by
-- service-public.gouv.fr (fiche F33371):
--
--   class 0 (vignette verte)  electric, hydrogen                  whatever the date
--   class 1                   gas (LPG, CNG), plug-in hybrid      whatever the date
--                             petrol, Euro 5 and 6                from 1 January 2011
--   class 2                   petrol, Euro 4                      1 January 2006 – 31 December 2010
--                             diesel, Euro 5 and 6                from 1 January 2011
--   class 3                   petrol, Euro 2 and 3                1 January 1997 – 31 December 2005
--                             diesel, Euro 4                      1 January 2006 – 31 December 2010
--   class 4                   diesel, Euro 3                      1 January 2001 – 31 December 2005
--   class 5                   diesel, Euro 2                      1 January 1997 – 31 December 2000
--   unclassified (6 here)     petrol or diesel, Euro 1 and before before 1 January 1997
--
-- A non-plug-in hybrid follows its engine's fuel: petrol or diesel.
--
-- The arrêté classes a car by its Euro standard (field V.9 of the registration certificate) and
-- uses the date only when the standard is not known. AutoX has the date, not the standard. A car
-- of the newer standard could be registered before that standard was required of every new car,
-- as soon as new types had to meet it; and an "end of series" car of the older one up to twelve
-- months after. In those windows the date does not settle the class, so the answer is NULL, not
-- a guess:
--
--   Euro 2  new types 1 January 1996,   all cars 1 January 1997  → unsure Jan 1996 – Dec 1997
--   Euro 3  new types 1 January 2000,   all cars 1 January 2001  → unsure Jan 2000 – Dec 2001
--   Euro 4  new types 1 January 2005,   all cars 1 January 2006  → unsure Jan 2005 – Dec 2006
--   Euro 5  new types 1 September 2009, all cars 1 January 2011  → unsure Sep 2009 – Dec 2011
--
-- Only where the two standards fall in different classes for that fuel: Euro 2 and 3 are both
-- class 3 for petrol, so the Euro 3 window does not matter to petrol. Without a month, a year is
-- settled only if every month of it is. Ethanol (E85) and "other" are not in the table's terms:
-- NULL. NULL is shown as nothing.

CREATE FUNCTION public.crit_air_class(fuel TEXT, registration_year INT, registration_month INT)
    RETURNS SMALLINT LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    WITH months AS (
        -- The months the registration may be in: one when the month is known, the twelve of the
        -- year otherwise. Months counted from year 0, so a date is one integer.
        SELECT m
          FROM generate_series(
                   registration_year * 12 + COALESCE(registration_month, 1) - 1,
                   registration_year * 12 + COALESCE(registration_month, 12) - 1) AS m
         WHERE registration_year IS NOT NULL
    ), classes AS (
        SELECT CASE
                   WHEN fuel IN ('PETROL', 'HYBRID_PETROL') THEN
                       CASE
                           WHEN m >= 1996 * 12 AND m < 1998 * 12 THEN NULL
                           WHEN m >= 2005 * 12 AND m < 2007 * 12 THEN NULL
                           WHEN m >= 2009 * 12 + 8 AND m < 2012 * 12 THEN NULL
                           WHEN m < 1997 * 12 THEN 6
                           WHEN m < 2006 * 12 THEN 3
                           WHEN m < 2011 * 12 THEN 2
                           ELSE 1
                       END
                   WHEN fuel IN ('DIESEL', 'HYBRID_DIESEL') THEN
                       CASE
                           WHEN m >= 1996 * 12 AND m < 1998 * 12 THEN NULL
                           WHEN m >= 2000 * 12 AND m < 2002 * 12 THEN NULL
                           WHEN m >= 2005 * 12 AND m < 2007 * 12 THEN NULL
                           WHEN m >= 2009 * 12 + 8 AND m < 2012 * 12 THEN NULL
                           WHEN m < 1997 * 12 THEN 6
                           WHEN m < 2001 * 12 THEN 5
                           WHEN m < 2006 * 12 THEN 4
                           WHEN m < 2011 * 12 THEN 3
                           ELSE 2
                       END
               END AS class
          FROM months
    )
    SELECT CASE
               WHEN fuel IN ('ELECTRIC', 'HYDROGEN') THEN 0
               WHEN fuel IN ('LPG', 'CNG', 'PLUGIN_HYBRID') THEN 1
               WHEN fuel IN ('PETROL', 'HYBRID_PETROL', 'DIESEL', 'HYBRID_DIESEL') THEN
                   -- Settled only if every possible month gives one same class, none of them unsure.
                   (SELECT CASE WHEN count(*) > 0 AND count(*) = count(class) AND min(class) = max(class)
                                THEN min(class) END
                      FROM classes)::SMALLINT
           END
$$;

COMMENT ON FUNCTION public.crit_air_class(TEXT, INT, INT) IS
    'Crit''Air class from fuel and first registration (arrêté du 21 juin 2016, annexe I): 0 electric, 1–5, 6 unclassified, NULL when the date cannot settle it. See V30.';

-- Stored, so the search filters on it like any column, and the rule exists once.
ALTER TABLE listing ADD COLUMN crit_air SMALLINT
    GENERATED ALWAYS AS (public.crit_air_class(fuel_type, first_registration_year, first_registration_month)) STORED;

COMMENT ON COLUMN listing.crit_air IS
    'Estimated Crit''Air class: 0 (vignette verte) to 5, 6 unclassified, NULL when it cannot be settled. Generated from crit_air_class.';
