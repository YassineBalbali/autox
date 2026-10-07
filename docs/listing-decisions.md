# Listing CRUD — decisions and open questions

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/listings` | creates a DRAFT, 201 + `Location` |
| GET | `/api/v1/listings/mine` | own listings, paginated, `?status=` filter |
| GET | `/api/v1/listings/{id}` | owner or ADMIN only |
| PATCH | `/api/v1/listings/{id}` | partial; cannot set status |
| POST | `/api/v1/listings/{id}/publish` | → ACTIVE, applies the cap, sets 60-day expiry |
| POST | `/api/v1/listings/{id}/unpublish` | → PAUSED, frees a quota slot |
| POST | `/api/v1/listings/{id}/renew` | extends 60 days, revives EXPIRED |
| DELETE | `/api/v1/listings/{id}` | soft delete, 204 |

Status is not a writable field anywhere. That is deliberate: because the only way to
change state is through these endpoints, there is no request shape that can bypass the
transition table.

---

## 1. A schema change I had to make: location fallback (`V7`)

`V4` made `listing.geo_place_id` **NOT NULL**, and the gazetteer is deferred. Those two
facts together mean no listing can be created at all — there is nothing to reference.

`V7` applies the same escape hatch the catalogue already has: `geo_place_id` is nullable,
with `postal_code` + `city` free text as the alternative, and a `CHECK` enforcing that one
of the two is present. Latitude and longitude became nullable because they are copied from
the referenced place.

This is a **new migration**, not an edit to `V1`–`V5` — those are now applied to your dev
database, so from here on they are immutable. (`V6` is the throttle table.)

What it costs: radius search cannot work for free-text listings until the gazetteer lands
and a backfill resolves them. Search is a later step, and `ix_listing_location_gap` already
indexes the queue of unresolved rows. **If you would rather seed a few hundred French
postal codes now and keep the reference mandatory, say so — this is reversible while
nothing is in production.**

## 2. Reinstating a removed listing is currently impossible

`ListingStatus` allows `REMOVED → ACTIVE` for an admin reinstate. But `DELETE` sets
`deleted_at`, and every read path filters on `deleted_at IS NULL` — so a removed listing
cannot be found, and therefore cannot be reinstated.

This is the two-mechanisms problem from note 5 of the schema decisions, now concrete.
Three ways out:

- **A — separate the two.** Seller delete sets `deleted_at` (gone for good); admin takedown
  sets `status = REMOVED` and leaves `deleted_at` null (reinstatable). Clean, and it makes
  each column mean one thing.
- **B — admin reads ignore `deleted_at`.** One repository method for admins that can see
  soft-deleted rows, plus a reinstate endpoint.
- **C — drop `REMOVED → ACTIVE`.** Removal is final; a seller re-lists instead.

**I would do A**, and it belongs with the moderation endpoints rather than here. Flagging
it now because the transition table currently promises something the API cannot deliver.

## 3. SOLD is unreachable

`SOLD` is in the enum and in the transition table, but there is no endpoint that sets it —
"mark as sold" was not in your list. It is the single most useful listing action after
publish, and without it sellers will delete listings instead, which loses the data that
makes price history possible later. One endpoint, `POST /{id}/sold`. **Say the word.**

## 4. Publish checks the quota and nothing else

Three gates that will eventually belong on publish are absent, each for a reason:

- **At least one photo.** Images are the next task; a listing with no photos is nearly
  worthless, so this should become a publish precondition then.
- **A verified email.** We agreed this gates publishing. Nothing sets `email_verified_at`
  yet, so enforcing it now would make publishing impossible.
- **Moderation.** The MVP publishes straight to ACTIVE by your earlier decision;
  `moderation_status` stays `NOT_REVIEWED` and the admin queue index is already there.

## 5. Expiry is a stored state, not a computed one

The sweeper runs every ten minutes and flips due listings to `EXPIRED`. The alternative —
treating "expired" as `expires_at < now()` everywhere — means the dashboard, the quota count
and search must each re-derive it, and they will eventually disagree. A listing may stay
visible a few minutes past its expiry; nobody is harmed by that.

The sweep is a single idempotent `UPDATE`, so running it on several instances at once is
safe. It is not locked, which means it may run redundantly, not incorrectly.

## 6. Renewal is measured from now, not from the old expiry

Renewing on day 50 gives 60 more days from that moment, not 70 days from publication.
Renewing early is therefore slightly "wasteful" for the seller — the alternative rewards
hoarding renewals. **Your call if you prefer the other behaviour; it is one line.**

## 7. Smaller things, flagged

- **A listing someone else owns returns 404, not 403.** A 403 confirms the id exists, which
  lets anyone enumerate listings. The tests assert 404 on read, update, publish and delete.
- **An optimistic-lock conflict now returns 409 `CONCURRENT_MODIFICATION`** instead of the
  500 it was returning. `@Version` was already on the entity; nothing mapped the exception.
  Found by review, not by a test — a genuine concurrency test needs two transactions in
  flight, which MockMvc does not give cheaply. Tell me if you want it as a slice test.
- **PATCH cannot clear an optional enum.** `null` means "unchanged", so there is no way to
  unset `bodyType` once set. Same `JsonNullable` trade-off as the profile PATCH. Clearing
  free-text fields works, via an empty string.
- **`featureCodes` replaces the whole set** rather than merging, and an empty array clears
  it. Add/remove semantics would need two more endpoints.
- **Catalogue entities gained `of(...)` factories.** The import job will need them, and it
  let the test fixtures stop using reflection. Sellers still cannot create catalogue rows.
- **Vehicle attributes are validated twice** — Bean Validation bounds mirror the table's
  `CHECK` constraints. Deliberate: the DTO turns a bad value into a 400 naming the field,
  and the constraint remains the backstop for any path that bypasses the DTO.
- **The `mine` list sorts by `updatedAt DESC`** with offset paging, which is right for a
  dashboard. Public search will use the keyset cursor the indexes were built for.
