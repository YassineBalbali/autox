# Public search — decisions, query plans and the language audit

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/listings` | none | search, cursor-paginated |
| GET | `/api/v1/listings/{id}` | none | public detail, ACTIVE only |
| GET | `/api/v1/listings/mine` | required | the seller's own list (unchanged) |
| GET | `/api/v1/listings/mine/{id}` | required | **moved** — was `/listings/{id}` |
| GET | `/api/v1/catalogue/suggestions?q=` | none | the search bar's typeahead; see below |

### The search bar's suggestions

- **What matches.** A make whose name starts with what was typed; a model whose own name
  does ("a3"), or whose make's name, a space and the start of its own do ("audi a"). Two to
  sixty characters; outer spaces dropped, inner runs counted as one.
- **Case and accents.** Both sides go through `catalogue_search_key()` (V16): PostgreSQL's
  `unaccent` with its dictionary named by schema, which is what makes the wrapper honestly
  `IMMUTABLE` and so indexable, then `lower()`. `unaccent` also folds ligatures (œ → oe),
  which a client-side `normalize('NFD')` does not.
- **The prefix test is a range** (`key ~>=~ q AND key ~<~ q || U+10FFFF`) on
  `text_pattern_ops` expression indexes, not `LIKE q || '%'`: the planner rewrites `LIKE`
  into that range only when the pattern is a constant at planning time, which a prepared
  statement's generic plan does not have. A typed `%` or `_` needs no escaping either.
- **No dead ends.** Counts join the listings under the search's own visibility rule; an
  entry with nothing online is not suggested. Makes first, then the most listed; eight at most.
- **Cached for a minute**, not the hour of `/reference`: it follows listings, not migrations.
  Rate-limited with the other public reads; the client asks after a 200 ms pause in typing.
- **Free-text makes are not suggested.** A listing whose make was typed rather than chosen
  has no catalogue id to filter on, so it cannot be a structured suggestion.

### One breaking change to last task's API

`GET /api/v1/listings/{id}` is now the public detail endpoint, so the seller's own view moved
to `/api/v1/listings/mine/{id}`. The alternative was one path returning two different shapes
depending on who asked, which no typed client can rely on. Nothing is deployed, so this costs
only the Angular work that has not started.

---

## Query plans: measured, and where they were wrong

Checked by `SearchQueryPlanTest` against **20 000 published listings** with statistics
analysed — ten rows would have proved nothing, because the planner sequentially scans small
tables whatever indexes exist. The test asserts the plans and prints them, so a regression
fails the build.

### What was already right

`NEWEST` — the default, and what nearly every visitor hits — was correct from the schema work:

```
Index Scan using ix_listing_live_published on listing l
  Index Cond: (ROW(published_at, id) < ROW('2026-10-07 14:32:20+01', '62d5d178-…'))
```

The keyset predicate is satisfied **by the index**, not by filtering, and there is no sort
node. Page 500 costs what page 1 costs.

### What was wrong

**`PRICE_ASC` / `PRICE_DESC` — full sequential scan.** `ix_listing_live_price` led with
`price_currency_code`, and search does not constrain the currency, so the index could not be
used for the ordering at all:

```
Limit -> Sort (Sort Method: top-N heapsort)
  -> Seq Scan on listing l  (actual rows=20000)
```

Twenty thousand rows read and sorted to return twenty-four.

**`MILEAGE_ASC` — index used, but then re-sorted.** `ix_listing_live_mileage` stopped at
`mileage_km`, so the total ordering `(mileage_km, id)` needed an extra step:

```
Limit -> Incremental Sort (Sort Key: mileage_km, id; Presorted Key: mileage_km)
  -> Index Scan using ix_listing_live_mileage
```

### The fix — `V9`

Both indexes now end on `id`, which is what a keyset sort needs to make its ordering total.
A btree scans in both directions, so one ascending index serves both price directions. After
`V9`:

| Sort | First page | Second page (the keyset path) |
|---|---|---|
| `NEWEST` | `Index Scan using ix_listing_live_published` | `ROW(published_at, id) <` as Index Cond |
| `PRICE_ASC` | `Index Scan using ix_listing_live_price` | `ROW(price_amount, id) >` as Index Cond |
| `PRICE_DESC` | `Index Scan Backward using ix_listing_live_price` | as Index Cond |
| `MILEAGE_ASC` | `Index Scan using ix_listing_live_mileage` | `ROW(mileage_km, id) >` as Index Cond |

No sequential scans and **no sort nodes** on any of the four.

### Where a plan is still bad, and why

**The `make` / `model` name filter cannot use an index.**

```
Filter: ((COALESCE(mk.name, l.make_text))::text ~~* 'BMW'::text)
Rows Removed by Filter: 16000
```

`COALESCE` over a joined column and a local column is not indexable, so this reads the whole
visible set. It is a direct consequence of the deferred catalogue: with no catalogue rows,
every listing identifies its vehicle by free text, so the indexed `makeId` filter would match
nothing and the name filter is the only one that works.

It resolves itself rather than needing work: once the catalogue is seeded the frontend sends
`makeId`, and `ix_listing_live_make_model_recent` / `_price` handle it. **If free-text volume
makes this hurt before then**, the fix is a partial expression index on
`lower(make_text)` plus rewriting that branch to an equality test — tell me and it is twenty
minutes. At 20 000 rows it is a few milliseconds and not worth the index write cost yet.

Two smaller notes: the `expires_at > now()` check appears as a Filter rather than an Index
Cond, because `now()` is not immutable and cannot go in a partial index predicate — it costs
nothing, since it is applied to rows already found by the index. And the joins to
`app_user`, `vehicle_make`, `vehicle_model` and `geo_place` are primary-key lookups with a
Memoize cache, which is as good as it gets.

---

## Keyset pagination, not offset

The cursor is base64 of `v1|SORT|sortValue|id` — opaque, so its contents can change without
breaking clients, and **bound to the sort it came from**: a cursor from a price-sorted page is
meaningless against a date-sorted query, so presenting one is a 400 rather than an arbitrary
slice of results.

There is **no total count**. Counting matches for an arbitrary filter combination is a second
full pass over the result set on every page, costing more than the page itself and growing with
the catalogue. Infinite scroll needs `nextCursor` and nothing else. When a result count becomes
a product requirement the answer is a cached approximate count or Elasticsearch, not
`COUNT(*)` on the hot path. **Say if the design needs "1 234 résultats" and I will plan it
properly.**

## Visibility

Public endpoints show a listing only when it is `ACTIVE`, not `REJECTED`, not soft-deleted,
**and not past `expires_at`**. That last one is belt and braces: the sweeper runs every ten
minutes, and an expired listing must not be visible in the gap. The SQL predicate and the Java
check in `detail()` say the same thing, and tests cover paused, sold, expired, deleted,
rejected and draft.

A draft returns 404 publicly **even to its owner** — the owner reads it under `/mine/{id}`.

---

## Language-neutrality audit

You asked me to confirm the API is language-neutral. Audited, and it is. What I checked:

**Every enum serialises as its constant name.** There are no `@JsonValue` or `@JsonProperty`
overrides anywhere in the codebase, so Jackson emits the constant. Sixteen enums a client must
map:

| Enum | Constants | | Enum | Constants |
|---|---|---|---|---|
| `FuelType` | 11 | | `ExteriorColor` | 14 |
| `Gearbox` | 3 | | `InteriorMaterial` | 6 |
| `BodyType` | 10 | | `EmissionClass` | 9 |
| `Drivetrain` | 3 | | `ImageStatus` | 3 |
| `VehicleCondition` | 3 | | `ImageVariantType` | 3 |
| `ListingStatus` | 7 | | `UserRole` | 3 |
| `ModerationStatus` | 3 | | `UserStatus` | 4 |
| `FeatureCategory` | 7 | | `DistanceUnit` | 2 |

**Equipment is returned as codes only.** `featureCodes` is an array of `BLUETOOTH`,
`NAVIGATION` and so on. `vehicle_feature_translation` holds the labels and is **not reachable
from any response** — verified; the only files referencing it are the entity and its own
mapping. The French labels seeded in `V5` are there for an admin tool, not for the API.

**No translatable display string is returned.** The only `getName()` calls in any DTO are
vehicle make, model and trim — `"BMW"`, `"3 Series"`, `"320d xDrive"` — which are proper nouns
and identical in every market. `country.name` is seeded in local language but is never
exposed; only `countryCode` is. `currency.symbol` is never exposed either.

**Error responses** carry a stable `code` plus, for validation failures, a `field`. The
`message` and `detail` members are English developer text by the decision from the auth work,
and `Accept-Language` is ignored. A test asserts two requests differing only in
`Accept-Language` return byte-identical bodies.

### One gap the frontend would have hit — now closed

**Nothing exposed the valid values of those enums, or the equipment vocabulary**, so Angular
would have had to hardcode both. The ~34 feature codes live in the database and a migration can
add one, which means a hardcoded copy drifts *silently* — the new equipment simply never
appears in the form.

Built in the following task as five reference endpoints under `/api/v1/reference`, with a test
that reflects over every request and response type and fails if an enum reachable from one is
not published. See **`docs/reference-decisions.md`**, including why the enums endpoint returns
values and not labels.

---

## Smaller things, flagged

- **Search is SQL, not JPA.** The row-comparison keyset predicate is the reason: expressed as
  JPQL's `a < ? OR (a = ? AND b < ?)` the planner does noticeably worse, and this is the hot
  path of the product. Search also returns a projection, so loading managed entities would be
  wasted work. Every filter value is a bound parameter; the only interpolation is of sort
  columns that come from an enum.
- **Cover images are fetched in one query per page**, not one per card.
- **Prices sort by `price_amount` across currencies**, which is meaningless if two currencies
  ever appear in one result set. Only EUR markets are enabled, so it is correct today. A real
  fix is a normalised price column maintained at write time; it needs an exchange-rate source.
- **`location` matches what the seller typed**, case-insensitively, against city or postal-code
  prefix. Accents are not normalised, so "Saint-Étienne" and "Saint-Etienne" differ — the
  gazetteer's `city_normalized` column solves this and `GeoPlace.normalize` already exists for
  it; it is not reachable until listings reference places.
- **The bounding-box filter is implemented and tested for rejection of a half-specified box**,
  but cannot match anything until listings carry coordinates, which needs the gazetteer. It is
  wired so that the day the data lands, radius search is a query-parameter change in the
  frontend.
- **Rate limiting on the public endpoints** was missing here and was added in the following
  task, reusing the login throttle's counter table per IP — `V10` generalises it rather than
  adding a second mechanism. Still true that a reverse proxy or CDN is the better place for it,
  and still true that the single-address key is weak against IPv6; both are written up in
  `docs/reference-decisions.md` and `docs/weaknesses.md`.
