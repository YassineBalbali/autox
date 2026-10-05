# AutoX — MVP data model

Scope: authentication, listings with photos, search, listing detail, admin
moderation. Nothing else is modelled.

## Tables at a glance

| Table | Purpose |
|---|---|
| `currency` | ISO-4217 currencies, with the minor-unit exponent |
| `country` | Markets, each with default currency, locale, phone prefix, on/off switch |
| `geo_place` | Postal-code gazetteer: city, admin regions, centroid |
| `app_user` | One account table for all three roles |
| `pro_profile` | Dealer-only business data, PK shared with `app_user` |
| `refresh_token` | One row per device session, hashed, rotating |
| `vehicle_make` | Manufacturer |
| `vehicle_model` | Model line, unique within make |
| `vehicle_trim` | Factory variant, unique within model |
| `vehicle_feature` | Equipment vocabulary (stable codes) |
| `vehicle_feature_translation` | Feature labels per locale |
| `listing` | The advert — the aggregate root |
| `listing_image` | Original photo, uploaded via pre-signed URL |
| `listing_image_variant` | Stored renditions of a photo |
| `listing_feature` | Listing ↔ feature join |
| `moderation_action` | Append-only admin audit trail |

## Relationships

```
currency 1 ──< country                     (country.default_currency_code)
currency 1 ──< app_user                    (preferred_currency_code, optional)
currency 1 ──< listing                     (price_currency_code)

country  1 ──< geo_place
country  1 ──< app_user
country  1 ──< listing

geo_place 1 ──< listing
geo_place 1 ──< pro_profile                (optional)

app_user 1 ──1 pro_profile                 (PK = FK; only for PROFESSIONNEL)
app_user 1 ──< refresh_token               (ON DELETE CASCADE)
app_user 1 ──< listing                     (as seller)
app_user 1 ──< moderation_action           (as admin)

vehicle_make  1 ──< vehicle_model 1 ──< vehicle_trim
vehicle_make  1 ──< listing                (optional — see catalogue gap)
vehicle_model 1 ──< listing                (optional)
vehicle_trim  1 ──< listing                (optional)

vehicle_feature 1 ──< vehicle_feature_translation   (ON DELETE CASCADE)
vehicle_feature >──< listing                        (via listing_feature)

listing 1 ──< listing_image 1 ──< listing_image_variant   (ON DELETE CASCADE)
listing 1 ──< moderation_action                           (ON DELETE CASCADE)
```

## The parts that carry the design

### Money
Every amount is `BIGINT` in the minor unit of an explicit `currency` FK. There is
no `DECIMAL` and no implicit "euros". A price is always the pair
(`price_amount`, `price_currency_code`), so a Swiss listing and a French one can
sit in the same table and the same result page.

### Location
`geo_place` is seeded per country at postal-code granularity and holds a
centroid. A listing FKs to it and copies `latitude`/`longitude` onto itself, so a
radius search is a bounding-box filter plus a haversine sort over one table — no
join, no PostGIS. `hide_exact_location` decides whether the API exposes anything
finer than that centroid.

### Vehicle identification
A listing identifies its vehicle through the catalogue (`make_id` → `model_id` →
`trim_id`), and falls back to free text (`make_text`, `model_text`,
`version_text`) when the catalogue has no match. Constraints enforce that:

- each of make and model is given exactly one way, id **or** text, never both and never neither;
- `model_id` requires `make_id`, `trim_id` requires `model_id`;
- composite FKs `(make_id, model_id)` and `(model_id, trim_id)` make an
  inconsistent pair — a Peugeot model under the BMW make — rejected by PostgreSQL
  rather than by a service method someone forgets to call.

Free-text listings surface in an admin queue via a partial index; once an admin
maps one onto catalogue rows, `catalog_gap_resolved_at` is stamped.

Technical attributes (fuel, gearbox, power, body) live **on the listing**, not
read through the trim. The seller can correct any of them, and a later catalogue
fix must never silently rewrite a published advert.

### Listing state
Two independent axes:

- `status` — the seller's: `DRAFT → ACTIVE → PAUSED / SOLD / EXPIRED / REMOVED`.
- `moderation_status` — the admin's: `NOT_REVIEWED / APPROVED / REJECTED`.

The MVP publishes straight to `ACTIVE` and moderates afterwards. `PENDING_REVIEW`
exists in the allowed set so pre-publish review can be enabled later without a
migration. A listing is publicly visible when
`status = 'ACTIVE' AND moderation_status <> 'REJECTED' AND deleted_at IS NULL` —
that exact predicate is the `WHERE` clause of every search index.

### Photos
`listing_image` is created with `status = PENDING` when the API issues a
pre-signed URL, and flipped to `READY` when storage confirms the object. Bytes
never pass through the API. Only object keys are stored, never full URLs, so the
bucket or CDN can change without rewriting rows. Each original has up to four
renditions in `listing_image_variant` with recorded dimensions, so the API can
emit a srcset and a phone downloads a thumbnail instead of a 6 MB original.

Gallery order is `listing_image.position`, unique per listing but `DEFERRABLE
INITIALLY DEFERRED`, so a drag-and-drop reorder commits as one transaction
without renumbering tricks.

### Pagination
No table is designed for offset paging. `listing` carries a UUIDv7 primary key,
which sorts by creation time, so every list endpoint can key-set paginate on
`(sort_key, id)` with a stable cursor — including under concurrent inserts, where
`OFFSET` skips and repeats rows.

### Deletion
`app_user` and `listing` are soft-deleted (`deleted_at`): a closed account must
not vacate the moderation trail. Rows that exist only to describe a parent
(images, variants, translations, features, refresh tokens, moderation actions)
cascade.

## Index strategy

Every public search index is **partial**, on the visibility predicate above, so
drafts, sold and removed stock stay out of the hot indexes:

- `(published_at DESC, id DESC)` — default "newest first" sort, and the keyset
  cursor: a page-2 query on `(published_at, id) < (…, …)` is served by an
  index-only scan with no sort step
- `(make_id, model_id, published_at DESC)` and `(make_id, model_id, price_amount)`
  — the dominant filter, once per supported sort. A composite index only avoids a
  sort for the column it ends on, so newest-first and cheapest-first each need
  their own shape; verified against 50 000 rows across 200 make/model pairs
- `(price_currency_code, price_amount)`, `first_registration_year`, `mileage_km` — range filters
- `geo_place_id` and `(country_code, latitude, longitude)` — location filters

Plus operational indexes: the seller dashboard `(seller_id, status, updated_at)`,
the moderation queue, the catalogue-gap queue, the expiry sweeper, and the
pending-upload cleanup.

Fuel type, gearbox and body type are deliberately **not** indexed on their own —
they are low-cardinality and only ever narrow an already-filtered set. Real index
tuning belongs after the first production query log, and full-text search is out
of scope until Elasticsearch arrives.
