# Crit'Air on listings

The listing page shows the Crit'Air class a car **would** have, estimated from its fuel and its
first registration, under the vignette's colours: « Crit'Air estimée d'après l'énergie et la date
de 1re immatriculation ». The search can keep only "Crit'Air n ou mieux".

## The source

The official classification of passenger cars (M1): **arrêté du 21 juin 2016 établissant la
nomenclature des véhicules classés en fonction de leur niveau d'émission de polluants
atmosphériques**, JORF n° 0145 du 23 juin 2016, NOR DEVR1608006A, annexe I. As summarised by
service-public.gouv.fr (fiche F33371, « Certificat qualité de l'air (vignette Crit'Air) »):

| Class | Fuel | Euro standard | First registration |
|---|---|---|---|
| 0 (vignette verte) | electric, hydrogen | — | whatever the date |
| 1 | gas (LPG, CNG), plug-in hybrid | — | whatever the date |
| 1 | petrol | Euro 5, 6 | from 1 January 2011 |
| 2 | petrol | Euro 4 | 1 January 2006 – 31 December 2010 |
| 2 | diesel | Euro 5, 6 | from 1 January 2011 |
| 3 | petrol | Euro 2, 3 | 1 January 1997 – 31 December 2005 |
| 3 | diesel | Euro 4 | 1 January 2006 – 31 December 2010 |
| 4 | diesel | Euro 3 | 1 January 2001 – 31 December 2005 |
| 5 | diesel | Euro 2 | 1 January 1997 – 31 December 2000 |
| non classé | petrol, diesel | Euro 1 and before | before 1 January 1997 |

A non-plug-in hybrid follows its engine's fuel (`HYBRID_PETROL` as petrol, `HYBRID_DIESEL` as
diesel).

## Where it is computed: once

`crit_air_class(fuel, year, month)`, an immutable SQL function (migration `V30`), stored on each
listing as the generated column `listing.crit_air`:
- 0 to 5;
- 6 for unclassified;
- NULL when it cannot be settled.

The listing page shows the API's value (`PublicListingDetail.critAir`). The filter is one
predicate in the search's `appendFilters`. There is no second copy of the table in Java or
TypeScript.

## When nothing is shown

The arrêté classes a car by its **Euro standard** (field V.9 of the registration certificate) and
uses the date only when the standard is unknown. AutoX has the date, not the standard. Around each
change of standard, the date does not settle it:
- a car of the new standard could be registered from the date new types had to meet it;
- an "end of series" car of the old one, up to twelve months after every new car had to.

In those windows the answer is **nothing, not a guess**:

| Change | New types | Every new car | Unsure |
|---|---|---|---|
| Euro 2 | 1 Jan 1996 | 1 Jan 1997 | Jan 1996 – Dec 1997 |
| Euro 3 | 1 Jan 2000 | 1 Jan 2001 | Jan 2000 – Dec 2001 (diesel only: Euro 2 and 3 are both class 3 for petrol) |
| Euro 4 | 1 Jan 2005 | 1 Jan 2006 | Jan 2005 – Dec 2006 |
| Euro 5 | 1 Sep 2009 | 1 Jan 2011 | Sep 2009 – Dec 2011 |

Also nothing:
- **Without a month**, a year counts only if every month of it is settled, and to the same class.
- **Ethanol (E85)**, which the table does not name, "other", or a missing date.

The seller's own "classe d'émission" (Euro standard) is not used: it is declared, not checked, and
the label says what the estimate is based on.

## The filter

"Crit'Air maximum" in the sidebar, URL key `critair_max` (0 to 5), API criterion `critAirMax`:
- It keeps listings whose class is at most that.
- An unclassified car (6), or one with no estimate (NULL), is left out once the filter is set.
- It works for sales and leasing offers alike.

## Low-emission zones

One line, no rules: each agglomeration sets the vignettes allowed in its ZFE and when, and they
change. The page says to check the city's rules, and names none.
