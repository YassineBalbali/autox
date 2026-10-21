# The price indicator

On a search result and on a listing's page, a three-segment meter and a word say where
the asking price sits against comparable cars: **Bonne affaire**, **Prix du marché** or
**Au-dessus du marché**. The sidebar can narrow a search to the good deals. This file is
the rule, why it is that rule, and how it is computed.

The code is `backend/src/main/java/com/autox/search/MarketPrice.java`. The rule is written
there once, as SQL, and every use reads that one fragment.

## The rule

A listing's **comparables** are the other live listings that have:

| | |
|---|---|
| Make and model | the same catalogue make and model |
| Engine | the same fuel, and a power within 15 % of the listing's |
| First registration | within two years either side |
| Mileage | the same band: under 30 000 km, 30–60 000, 60–100 000, 100–150 000, 150 000 and over |
| Currency | the same |
| Condition | used. A new car is neither judged nor used as a yardstick |

"Live" means what search means by it: active, not rejected by moderation, not deleted,
not past its expiry.

With **at least five** comparables, the listing's price is set against their **median**:

| Price against the median | Verdict | API code |
|---|---|---|
| 10 % or more below | Bonne affaire | `GOOD_DEAL` |
| within 10 % either side | Prix du marché | `MARKET` |
| 10 % or more above | Au-dessus du marché | `ABOVE_MARKET` |

With fewer than five, nothing is shown: no badge, no "Prix du marché". The same applies to
a new car, to a vehicle identified by typed text instead of the catalogue, and to a listing
that does not state its power.

## Why these choices

- **The median, not the mean.** One price typed with an extra zero would drag a mean
  anywhere. A median ignores it.
- **Five comparables.** Below that, one or two odd prices decide the median. The minimum
  costs coverage: a rare model never gets a verdict. That is the right way round. A badge
  that is sometimes absent can be trusted when it is there.
- **Year ±2 and mileage bands, not ±N km.** Fixed bands are easy to explain to a buyer and
  to index. A sliding window has no edge where a car's comparables suddenly change, but it
  is harder to say what it means. Bands are narrow at low mileage, where a buyer cares
  most, and wider above.
- **Per version, not per model.** Real valuation is per version: a Tesla Model 3
  Performance is not an overpriced Model 3 Propulsion, and comparing them is wrong
  whatever it does to the number of badges. Compared per model, every Propulsion came out
  a good deal and every Performance above the market. Showing fewer verdicts that can be
  defended beats showing many that mislead.
- **Fuel and power stand in for the version.** AutoX has no version catalogue yet (see
  `docs/deferred.md`), and the version text is free and spelt differently by every seller.
  Fuel and power are structured, and together they nearly always tell a model's engines
  apart: a 308 PureTech 130 and a 308 Hybrid 180 differ in both. 15 % is enough for the same
  engine across a facelift (130 then 136 ch) and narrower than the gap between the engines
  a model is sold with. Trim and options are still ignored, which is one reason the margin
  is 10 %, not 5 %. The listing page says what the verdict rests on, as in "Comparé à
  7 Peugeot 308 de même motorisation de 2021 à 2025 au kilométrage proche, prix médian
  21 490 €", so a buyer can judge it.
- **The listing itself is not one of its own comparables.** If it were, an overpriced car
  would raise its own median.

## How it is computed

**When it is read, not stored.** The query that reads the listings joins each one to its
comparables (`LEFT JOIN LATERAL … percentile_cont(0.5)`) and works out the verdict there.

A stored figure would need refreshing whenever any comparable listing changes price,
sells, expires, is withdrawn or is rejected. That happens in half a dozen places, and
missing one leaves a wrong badge that nobody notices. A verdict computed on read cannot be
stale.

**The cost:**

- **A page of results** costs one extra query for the page's 24 listings, using
  `ix_listing_live_market (model_id, first_registration_year)`, a partial index over live
  listings (V14). Searches that don't filter on the price level are otherwise unchanged:
  the comparables join is added to the search query only when that filter is on.
- **The "Bonnes affaires" filter** joins every candidate listing to its comparables, so
  it is one index range scan per candidate. That is fine for this catalogue. At a national
  marketplace's size, this would become a table maintained on write, by a job or by the
  listing service's writes, filled by the same SQL. Nothing the API returns would change.

## The API

- **`priceIndicator`** on `PublicListingSummary` (search results) and
  `PublicListingDetail`: `{ level, medianPriceAmount, comparableCount }`, or absent. The
  median is in minor units of the listing's currency, like every price in the API.
- **`priceLevel`** on `GET /api/v1/listings` and `/count`, repeatable:
  `?priceLevel=GOOD_DEAL`. A listing without an indicator matches no level. The site's URL
  for it is `/recherche?bonnes_affaires=1`.

## The demo data

Seeded at random, the indicator would have nothing to say: a hundred and twenty listings
over twenty-one models, each in several versions, and almost none would reach five
comparables of the same engine. The seed (`DemoCatalogue`) therefore puts about ten
listings on each of eight common models, most or all of them in the one version that
sells: the Clio TCe 90, the Model 3 Propulsion, the Sandero Stepway ECO-G 100. The other
models get one to four listings, which shows the indicator staying silent. Asking prices
are spread around each car's value, as sellers' prices are, so all three verdicts appear.
About half the listings (57 of 120) get one.

`DemoCatalogueTest` applies the rule to the seed in memory. It fails if fewer than thirty
listings get a verdict, if any verdict is missing, or if one of the eight volume models
gets none.

## A known weakness

The comparables are not adjusted for age or mileage within the window. A 2024 car is
compared with 2022 and 2023 ones, which are worth less, so a fairly priced car at the
young end of the window can come out "Au-dessus du marché", and one at the old end a
"Bonne affaire". On the demo seed, where the true value of each car is known, 3 of the 24
good-deal and above-market verdicts are of this kind. A narrower window would mean fewer
verdicts. The better fix is to adjust each comparable's price to the listing's age and
mileage before taking the median.
