# Schema decisions I am not certain about

Each entry: what I chose, why, what the alternative is, and what it would cost to
switch later. Ordered roughly by how expensive the mistake would be.

---

## 1. Enum values live in `CHECK` constraints

Chosen: `VARCHAR` + `CHECK (col IN (...))`, mirrored by a Java enum.

Adding a fuel type means a migration that rewrites the constraint, and the Java
enum and the SQL list can drift apart with nothing to catch it.

- **Alternative A — PostgreSQL native `ENUM`.** `ALTER TYPE ... ADD VALUE` is
  cheap, but removing or renaming a value is painful and Hibernate needs extra
  mapping.
- **Alternative B — lookup tables** (`fuel_type(code, …)` with FKs). Values
  become data, translatable in the same place as features, and admin-editable.
  Costs a join per facet and makes every enum a managed entity.

I would add a test that asserts each Java enum's constants equal the values in
the matching constraint — cheap, and it removes the drift risk entirely. Say the
word and I will write it with the schema.

**Switch cost later:** moderate for A, high for B (data migration + API contract).

---

## 2. Trim as a full catalogue level

Chosen: `vehicle_make → vehicle_model → vehicle_trim`, trim optional.

Trim data is the expensive part of any vehicle catalogue: tens of thousands of
rows per market, and it dates fast. The MVP search does not filter on trim.

- **Alternative — drop `vehicle_trim` now** and keep only `version_text` free
  text on the listing. Adding trims later is additive: a new table plus a
  nullable FK, no data loss.

I kept it because it is the foundation for price estimation later, and because
removing a table I do not use is cheaper than retrofitting one I need. But if
catalogue sourcing turns out to be a blocker, cut this first.

**Switch cost later:** low in either direction.

---

## 3. No catalogue or gazetteer data is seeded

`V5` seeds currencies, countries and the equipment vocabulary only. Makes,
models, trims and `geo_place` are bulk datasets and I did not want to invent
them.

They need real sources, and the licensing matters:

- **Geo (FR):** La Poste's *Base officielle des codes postaux* (open licence) or
  GeoNames (CC BY). Either gives postal code, city and centroid directly. ~39 000
  rows for France.
- **Makes/models:** no clean free source. Realistic options are scraping a public
  catalogue (licence risk), a paid feed (JATO, DAT, Autovista — not student
  budget), or hand-curating the ~60 makes and ~1 500 models that cover the French
  market, which is a weekend of work and is what I would do.

These belong in a repeatable loader, not a hand-written migration. **Tell me
which sources you want and I will write the import as a separate Flyway
migration plus a CSV in `backend/src/main/resources/db/seed/`.**

---

## 4. `listing.view_count` is a column on the hot row

A counter on the listing row means every detail-page view writes to the row every
search result reads — row contention and index churn under load, for a number
nobody needs to be exact.

- **Alternative A — separate `listing_stat` table**, one row per listing, written
  asynchronously. Keeps writes off the listing row.
- **Alternative B — count views in Redis** and flush periodically.

I kept the column because at MVP traffic it is free and it is one fewer moving
part; it is mapped read-only in JPA so nothing writes it through Hibernate. If
you would rather not carry the wart at all, say so and I will drop it from `V4`
— adding it back is a one-line migration.

---

## 5. Soft delete has no enforcement

`app_user.deleted_at` and `listing.deleted_at` are plain nullable columns. Any
query that forgets `deleted_at IS NULL` silently returns deleted rows.

- **Alternative A — Hibernate `@SQLRestriction`** on the entity. Automatic, but
  it then applies everywhere, including the admin screens that need to see
  deleted rows.
- **Alternative B — no soft delete on `listing`**; the `REMOVED` status already
  covers takedowns, and `deleted_at` partly duplicates it.

B is worth a conversation: two "this is gone" mechanisms on one table is a smell.
My reasoning for keeping both is that `REMOVED` is a *state the seller sees* and
`deleted_at` is *a row nobody should ever see again*, but you could collapse them
into one status.

---

## 6. Listing expiry exists, and I chose nothing for it

I added `expires_at` because marketplaces full of dead listings lose buyer trust,
and retrofitting expiry onto live data is unpleasant. The brief does not mention
it.

Unanswered: how long (La Centrale ≈ 60 days, Leboncoin 60), whether expiry
differs by role or by subscription tier, and whether expired listings stay
readable by direct URL. **These are product decisions — tell me and the sweeper
job implements them. The column costs nothing while unused.**

---

## 7. Equipment as a join table, not an array

`listing_feature` is a classic many-to-many.

- **Alternative — `TEXT[]` or `JSONB` on `listing`** with a GIN index. Fewer
  tables, one fewer join, and PostgreSQL indexes `@>` well.

I chose the join table because features need translating and curating, which
wants a real `vehicle_feature` row. If equipment turns out to be display-only and
never a filter, the array is the simpler model.

---

## 8. One description, one language

`listing.description` is a single text column. A Belgian dealer cannot publish FR
and NL versions of the same advert.

- **Alternative — `listing_translation(listing_id, locale, title, description)`.**

For the France-first MVP this is clearly unnecessary. For Belgium and Switzerland
it is a real requirement. It is additive (a new table, existing columns become
the default locale), so I deliberately deferred it — but it is a decision, not an
oversight.

---

## 9. Personal data I chose to store

- `refresh_token.ip_address` and `user_agent` — useful for a "your sessions"
  screen and for detecting token theft. Under the GDPR they are personal data
  with no retention rule yet. **Option:** drop the IP and keep only the device
  label.
- `listing.vin` — a VIN identifies a vehicle and indirectly its owner. It is
  never returned by the public API. Mainly useful for duplicate detection.
  **Option:** drop it from the MVP.
- Account deletion is a soft delete, so an erasure request means *anonymising*
  `app_user` (hash the email, clear names and phone), not deleting the row.
  Worth writing down now rather than discovering at launch.

---

## 10. Where I disagree with the brief

**Elasticsearch is further off than you think.** PostgreSQL full-text with a
`tsvector` column plus `pg_trgm` for fuzzy make/model matching will carry this to
a few hundred thousand listings. The MVP search you specified is pure structured
filtering — no free-text box at all — so the schema has no FTS index yet. Adding
one is a single migration. I would not stand up a second datastore until search
is demonstrably the bottleneck, and when it is, a cleanly indexed PostgreSQL
makes the Elasticsearch projection easy.

**"Limited active listings" for PARTICULIER is not enforced by the database.**
There is no constraint that can count rows per seller. `app_user.active_listing_limit`
holds the override and the backend enforces it on publish. If you want that
guarantee at the data layer it needs a trigger, which I would not recommend.

**Mileage is always stored in kilometres**, with `country.distance_unit` as a
display hint. The moment the UK is in scope, storing whatever the seller typed
would break every range filter.

---

## 11. Smaller things, flagged for the record

- **UUIDv7 is generated in application code** (`com.autox.common.UuidV7`) rather
  than by a Hibernate generator or a database default, so every write path — JPA,
  batch loader, test fixture — produces the same layout and a client can be told
  an id before the row exists. The seed migration uses `gen_random_uuid()` (v4)
  for reference rows, where ordering is irrelevant.
- **Join tables keep composite primary keys** (`listing_feature`,
  `vehicle_feature_translation`) rather than a surrogate id.
- **`listing_image.position` is `UNIQUE … DEFERRABLE INITIALLY DEFERRED`** so a
  gallery reorder commits in one transaction. It requires that reorders actually
  run inside a transaction.
- **No build file yet.** The entities need a `pom.xml` (Spring Boot 3.x, Java 21,
  `spring-boot-starter-data-jpa`, `flyway-core`, `postgresql`) before anything
  compiles. I left it out because you asked for the schema only, and because the
  Spring Boot version should be whatever Initializr currently pins. Say the word
  and it is the next five minutes of work.
- **`CHAR(n)` became `VARCHAR(n)` in V1, V2 and V4** (amended after the fact, see
  below). Hibernate's `ddl-auto: validate` rejected `bpchar` against a `String`
  field, and `char(n)` has no advantage in PostgreSQL — it only adds space
  padding that bites on comparison. The format `CHECK` constraints already pin the
  exact length. `refresh_token.ip_address` moved from `inet` to `VARCHAR(45)` for
  the same reason: it is an audit field, never matched against a subnet.
  **These edits changed migrations you had already committed.** That is safe only
  because no environment had run them yet. From the first real deployment onwards,
  an applied migration is immutable and a change like this needs a new `V_`
  file — Flyway's checksum validation will refuse otherwise.
- **Nothing is validated twice yet.** The `CHECK` constraints are the backstop;
  Bean Validation annotations on the entities would duplicate them. I would put
  validation on the request DTOs instead, when we get there.
