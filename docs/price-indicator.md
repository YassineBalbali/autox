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

Each comparable's price is then **brought to the listing's age and mileage**, using the
depreciation assumption below. A comparable registered a year before the listing is
counted at its price plus a year's depreciation. One with 10 000 km more is counted at its
price plus that much wear, and the reverse in both cases. The month of registration
counts; a listing that gives none is taken as registered mid-year.

With **at least five** comparables, the listing's price is set against the **median** of
those adjusted prices:

| Price against the median | Verdict | API code |
|---|---|---|
| 10 % or more below | Bonne affaire | `GOOD_DEAL` |
| within 10 % either side | Prix du marché | `MARKET` |
| 10 % or more above | Au-dessus du marché | `ABOVE_MARKET` |

With fewer than five, nothing is shown: no badge, no "Prix du marché". The same applies to
a new car, to a vehicle identified by typed text instead of the catalogue, and to a listing
that does not state its power.

## The depreciation assumption

| | | Constant in `MarketPrice` |
|---|---|---|
| Age | a used car loses **10 % of its value a year** | `YEARLY_DEPRECIATION` |
| Mileage | it loses **2 % of its value per 10 000 km** | `DEPRECIATION_PER_10K_KM` |

Both are multiplicative. A comparable two years older than the listing is counted at its
price ÷ 0.9², about +23 %. One with 20 000 km less is counted at its price × 0.98², about
−4 %.

**Where it comes from.** These are round rules of thumb, not fitted to data. Figures commonly
quoted for the French used market put a car's loss at roughly a fifth to a quarter in its
first year, then around 10 to 15 % a year, with mileage costing a few per cent of the value
per 10 000 km. The assumption takes the low end of the yearly range and a round figure for
mileage. It is not taken from any published table. It is deliberately not the demo
generator's model either (11 % a year, 15 % for electric cars, 1.5 % per 10 000 km): real
listings will not come from the generator, and an indicator checked against the model that
generated its test data would agree with it by construction.

**Its limits.**

- **One rate for every car.** Electric cars have lost value faster than that in recent
  years, and some models hold their value far better than others. For a car that really
  loses 15 % a year, comparables two years older are under-adjusted by about 11 %, which is
  the width of the "Prix du marché" band.
- **No first-year cliff.** A car loses most in its first year. A comparable a year older than
  a nearly new listing is under-adjusted. New cars are not judged, but one- and two-year-old
  ones are.
- **Mileage as a straight percentage.** Buyers react to thresholds: a car over 100 000 km
  sells at a discount beyond what 2 % per 10 000 km gives. The mileage bands contain this,
  since an adjustment never spans more than one band.
- **Only within the window.** The adjustment is applied to differences of at most two years
  and one band. That keeps the error of a wrong rate small. It does not make the
  assumption right.

The way to replace it is to estimate the rates from AutoX's own listings, per model or per
segment, once there are enough of them: a regression of log price on age and mileage. The
two constants are the only things that would change.

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
  7 Peugeot 308 de même motorisation de 2021 à 2025 au kilométrage proche. Prix médian,
  ramené à l'âge et au kilométrage de cette voiture : 21 490 €", so a buyer can judge it.
- **Adjusted prices, not a narrower window.** A ±1 year window and narrower mileage bands
  would also stop a 2024 car being compared with 2022 ones. They would also leave most
  listings without five comparables. Adjusting keeps the comparables and removes most of
  the age and mileage differences between them.
- **The median shown is the adjusted one.** `medianPriceAmount` is what a comparable car of
  this age and mileage would be listed for, which is the figure the verdict compares with.
  A median of raw prices would show a 2024 car a figure for 2023 ones.
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

## La Cote: the same engine for a car that is not listed

La Cote (`/la-cote`) is the indicator run the other way round. The indicator asks where a
listing's price sits among its comparables. La Cote takes a car a visitor describes and asks
what its comparables are listed for. The code is
`backend/src/main/java/com/autox/search/LaCote.java`.

### The method

- **The same rule.** The described car enters the indicator's query as a listing of its
  own: a one-row table with the columns the rule reads, joined to `MarketPrice.comparable`.
  The comparables, the adjustment to age and mileage, and the minimum of five are therefore
  those above, with no second copy of the rule.
- **Identifying the engine.** There is no trim catalogue, so the visitor chooses among the
  model's engines listed now (`GET /valuation/engines`). Each is a fuel and a power, named
  by the version text sellers give it most often. "Autre motorisation" takes a fuel and a
  power for an engine that is not listed.
- **What is ignored.** The gearbox and the version name are asked for but not compared on,
  because the indicator doesn't compare on them either. They only prefill the two links
  shown after an estimate. La Cote and the badge can therefore never disagree about the
  same car. The page says the gearbox is not taken into account.
- **The figure.** The **median** of the adjusted prices.
- **The range.** The **interquartile range**: a quarter of the comparables are below the low
  figure and a quarter above the high one. A single figure from five to ten asking prices
  would claim more precision than it has.
- **Rounding.** All three figures are rounded to the nearest 100 €, for the same reason.

**Fewer than five comparables means no figure.** The page says how many were found and that
it takes five. It then offers the loosenings of the rule that would reach five, each with
its count. Each loosening changes exactly one thing:

| Scope | Loosening | The page says |
|---|---|---|
| `ALL_ENGINES` | any fuel and power of the model | "ne tient pas compte de la puissance ni du carburant" |
| `WIDER_YEARS` | four years either side instead of two | prices brought further, so less precise |
| `ANY_MILEAGE` | any mileage band | prices brought further, so less precise |

A widened estimate carries an "Estimation élargie" tag and that sentence. A loosening that
still finds fewer than five is not offered. Even widened, no figure ever comes from fewer
than five.

**What the page shows the estimate rests on:**
- how many listings;
- the years and mileages of the comparables found;
- the criteria they met;
- that these are **asking prices on AutoX, not transaction prices**.

### Its limits

Everything listed above about the indicator applies, plus the following:

- **Asking prices are not sale prices.** Cars usually sell a little below their asking
  price, and La Cote doesn't know by how much. It reports what sellers ask and says so,
  rather than applying an invented discount.
- **The market is AutoX's alone.** Five comparables on AutoX is a small sample of the French
  market. A model that is rare here has no estimate, even if it is common elsewhere.
- **Widening leans on the assumption.** Four years apart is twice the window the
  depreciation rate was meant for. Over two years a wrong rate costs a few per cent;
  over four it costs twice that. That is why a widened estimate says it is less precise.
- **Condition, trim and options are not known.** Two cars with the same engine, age and
  mileage can differ by a fifth in value. The range shows part of that spread, not all of it.
- **Listings saved before the power was asked for don't count.** The listing form now
  requires a power in ch, and the API requires one on create. Listings saved before that
  have none, so they get no verdict and don't count for La Cote until their seller adds
  one. The form still lets them be edited and published without it, and tells the seller
  why to add it. When the seller has chosen a catalogue model, the form offers that
  model's engines as listed on AutoX. One click fills the fuel and the power, and the
  seller can correct either.

### The API

- **`GET /api/v1/valuation`**
  - **Parameters:** `modelId`, `fuelType`, `powerHp`, `year`, `month` (optional),
    `mileageKm`, `scope` (default `EXACT`), `currency` (default `EUR`).
  - **Returns:** `{ scope, comparableCount, minimumComparables, estimate?, basis?, window,
    widerScopes }`, with amounts in minor units.
  - **Errors:** 404 for an unknown model, 400 for a value out of range.
- **`GET /api/v1/valuation/engines?modelId=`**
  - **Returns:** the model's listed engines, most listed first.
- **Both:** public, and rate-limited per address with the rest of the public read API.

## The demo data

Seeded at random, the indicator would have nothing to say: a hundred and twenty listings
over twenty-one models, each in several versions, and almost none would reach five
comparables of the same engine. The seed (`DemoCatalogue`) therefore puts about ten
listings on each of eight common models, most or all of them in the one version that
sells: the Clio TCe 90, the Model 3 Propulsion, the Sandero Stepway ECO-G 100. The other
models get one to four listings, which shows the indicator staying silent. Asking prices
are spread around each car's value, as sellers' prices are, so all three verdicts appear.
About half the listings (57 of 120) get one: 16 good deals, 29 at the market price and 12
above it.

`DemoCatalogueTest` applies the rule to the seed in memory. It fails if fewer than thirty
listings get a verdict, if any verdict is missing, or if one of the eight volume models
gets none.

The seed knows each car's true value, so the verdicts can be checked against it. Without
the age and mileage adjustment, 3 of the 24 good-deal and above-market verdicts were wrong
way round: fairly priced cars called a good deal or above the market only because they were
at the old or the young end of the window. With it, none of 27 is. One is stronger than the
truth warrants: a 2023 Peugeot 208 asked 2 % under its value is called a good deal. That is
the residue of an assumption that differs from how the seed's prices were made, which is
what real prices will do too.

**La Cote on the same data.** Valuing the car of each of the 120 demo listings, as if its
owner asked:
- **57** get an estimate.
- **17** get none, but are offered a wider search that would give one.
- **46** get neither: the models with only one to four listings.

Two examples:
- **2024 Clio TCe 90, 39 600 km**, listed at 10 590 €: estimated at 13 200 € (range
  12 000–14 100 €) from 7 listings. The indicator calls the same listing a good deal.
- **2021 3008 BlueHDi 130, 53 400 km:** only one comparable in its mileage band, so no
  figure. Widened to every mileage: 18 400 € (17 300–20 000 €) from 10 listings.
