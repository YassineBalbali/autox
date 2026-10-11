# What is deliberately not built

A register of everything knowingly left out, so none of it gets lost between sessions or
rediscovered as a surprise. Nothing here is an oversight. Each entry says **what is missing**,
**what it costs today**, and **what already exists to build on** — because the expensive part
of resuming work is remembering which half of it is already done.

Three headings:

- **Data we do not have yet** — the code paths exist, the rows do not.
- **Scope the brief put out of the MVP** — not to be built or scaffolded, but not to be
  blocked either.
- **Work the MVP itself still needs** — the honest gaps in what is supposedly finished.

---

## 1. Data we do not have yet

### The vehicle catalogue (make / model / trim)

Three tables, empty. `vehicle_make`, `vehicle_model` and `vehicle_trim` are modelled,
constrained and indexed; no rows have been sourced.

**What exists.** Listings may reference the catalogue **or** carry free text — an XOR CHECK in
`V4` enforces exactly one, and the composite foreign keys
(`fk_listing_make_model`, `fk_listing_model_trim`) stop a model being paired with the wrong
make. Search accepts `makeId` *and* a free-text `make` name for the same reason. Fixtures in
`ListingTestFixtures.ensureCatalogue()` create catalogue rows the way an import job would, so
the referenced path is tested and not merely written.

**What it costs today.**

- `GET /api/v1/reference/makes` returns `[]`, so a frontend cannot offer a dropdown and must
  fall back to a free-text field.
- **Search cannot use an index for a make or model name.** `COALESCE(mk.name, l.make_text)`
  is not indexable, so that filter scans the whole visible set. Measured: 16,000 rows removed
  by filter out of 20,000. A few milliseconds at this size, and it resolves itself the day the
  frontend sends `makeId` instead.
- Free text means `BMW`, `bmw` and `B.M.W.` are three different manufacturers to the database.
  Whatever is typed before the catalogue lands will need reconciling against it afterwards.

**To finish it.** A data source (a commercial feed, or something like the EU type-approval
dataset), an import job, and a migration or admin endpoint to run it. Then the search filter
switches to ids and the free-text columns become a fallback for genuinely unlisted vehicles.

### The gazetteer (postal codes, cities, coordinates)

`geo_place` is modelled and empty.

**What exists.** `V7` made `geo_place_id`, `latitude` and `longitude` nullable and added
`postal_code` and `city`, with `ck_listing_location_known` requiring *some* location and
`ck_listing_coordinates_paired` stopping a half-set coordinate pair. The bounding-box filter
is implemented and tested — including that a half-specified box is rejected rather than
silently ignored. `GeoPlace.normalize` exists for accent folding.

**What it costs today.**

- **No radius search** — "within 50 km of Paris" is the single most-used filter on a car
  marketplace and it cannot be answered. The bounding box is wired and will match nothing
  until listings carry coordinates.
- Location matching is a case-insensitive comparison against what the seller typed, so
  `Saint-Étienne` and `Saint-Etienne` are different places.
- `ix_listing_location_gap` exists to find the listings needing backfill once data arrives.

**To finish it.** For France, the official base of postal addresses is open data. Load it,
match existing listings by postal code, backfill coordinates, then switch the location filter
to the bounding box. Radius search becomes a query-parameter change in the frontend.

---

## 2. Scope the brief put out of the MVP

Not built and **not scaffolded** — no dead tables, no unused endpoints. What follows is only
the note that nothing blocks them, and where each one attaches.

| Feature | Not blocked because |
|---|---|
| **Messaging** (buyer ↔ seller) | A listing has a stable id and an owner; a conversation table references both. Listings are soft-deleted, so a thread never dangles. Needs its own decisions: notifications, abuse reporting, whether contact details are hidden until contact is made. |
| **Favourites / saved searches** | A favourite is a user–listing pair. A saved search is this API's own query string, which is already a stable, documented contract — that is worth more than it sounds. |
| **Subscriptions and payments** | `pro_profile` exists; `app_user.active_listing_limit` already overrides the per-role cap per account, which is the hook a paid tier needs. `professionnel-active-limit: 0` means uncapped today, so introducing tiers is a configuration change plus billing, not a rework. |
| **AI assistant, price estimation, AutoX Score** | All read-only consumers of listing data. Python/FastAPI alongside, reading the same database or a feed from it. The data they need is now being retained — see below. |
| **Seller verification, vehicle inspection** | `app_user` carries `email_verified` and `phone_verified` flags, unused. `pro_profile` has the company fields. |
| **Elasticsearch** | Search is one `ListingSearchRepository`, behind one service. It is SQL today because keyset pagination over an index is genuinely fast and a second datastore is a replication problem. Swap it when relevance ranking, typo tolerance or faceted counts become requirements — all three are reasons PostgreSQL stops being enough, and none is an MVP need. |

**The data the deferred ML work needs is now being retained.** `sold_at` records the valuable
signal — what sold, at what asking price, after how long — and as of `V11`
`listing_price_history` records the rest of it: the price a listing opened at, and every
change after it. That gap was flagged here as *"cheap now and impossible retroactively"*,
which is why it was closed before the frontend started creating real listings rather than
left on this list.

It is deliberately **not exposed by any endpoint**. It is an input to a model, not a feature;
turning it into one is a product decision. Draft edits before first publication are not
recorded, because a seller typing three numbers into a form is not a market signal — the
reasoning is in `docs/account-and-correctness-decisions.md`.

---

## 3. Work the MVP itself still needs

### Admin moderation endpoints — the largest gap

`GET /api/v1/admin/users` is the only admin endpoint. The *schema* for moderation is complete
and unused: `listing.moderation_status`, `moderation_action` with its action types, and
`ModerationActionType`.

**What this means.** Nothing can take a listing down. A fraudulent or illegal listing can only
be removed by someone editing the database. For a marketplace with public, unauthenticated
listings that is the gap with real legal exposure, not merely a missing feature.

**Already decided, waiting to be built** (agreed earlier in this project):

- A **seller delete** sets `deleted_at` — permanent, not reinstatable.
- An **admin takedown** sets `REMOVED` only — reversible.
- At that point, **restore `REMOVED → ACTIVE` in the `ListingStatus` transition table.** It is
  currently terminal, deliberately: the table does not promise a transition no endpoint can
  perform. `ListingStatus` carries the comment, and this is the one place in the codebase where
  the state machine is knowingly narrower than the intended design.

Endpoints needed: approve, reject with a reason, take down, reinstate, and a moderation queue
(cursor-paginated, like search).

### Account lifecycle — mostly built

| Was missing | Now |
|---|---|
| **Password change** | **Built.** `POST /api/v1/me/password`. Requires the current password; replaces every session and returns a new token pair. |
| **Password reset** | **Built.** `POST /api/v1/auth/password/forgot` and `/password/reset`. Single-use token, 30-minute TTL, generic response either way. |
| **Session list / revoke** | **Built.** `GET /api/v1/me/sessions` and `DELETE /api/v1/me/sessions/{id}`. There is no separate "sign out everywhere" — changing the password does it. |
| **Email verification sending** | **Still missing.** `email_verified` exists and is never set true, so addresses are unverified and a seller may be unreachable. A reset deliberately does not set it either: verification is a flow that does not exist, and inventing half of it inside the password form is how two half-flows end up disagreeing. |

What is left is **a real mail provider**. `EmailSender` is an interface with a logging
implementation that prints the message — including the reset link — to the console. That makes
the flow walkable in development and is a credential leak in production; it warns at every
startup. Add a bean conditional on `autox.mail.provider` and set the property. Email
templating and localisation go with it: the recipient's locale already travels on every
`EmailMessage`, and the bodies are English.

### Content safety on uploads

No virus scanning and no image moderation. A seller can upload anything that decodes as an
image, and it is then publicly readable. EXIF including GPS is stripped (`ImageMetadata`), so
the privacy side is handled; the content side is not. Belongs with the moderation work.

### Image formats

JPEG and PNG only, narrower than the database CHECK allows. WebP and AVIF need a decoder
plugin (TwelveMonkeys or `imageio-webp`); accepting a format the server cannot resize would
produce a listing with no gallery. WebP output would cut gallery bytes by roughly 25–30% at
equal quality — a dependency and a decision, not a line of config.

### Operational gaps

- ~~**No health or readiness endpoint.**~~ **Built.** `/actuator/health` plus separate
  `/liveness` and `/readiness` probes, public, status only. Object storage is deliberately not
  a health signal: an S3 outage should degrade image upload, not empty the load balancer.
- **No metrics.** No request rates, no latency percentiles, no visibility into how often the
  rate limiter or the login throttle fires in production. The actuator dependency is now
  present, so this is configuration plus a scrape target — but `management.endpoints.web.exposure.include`
  is an allow-list of `health` on purpose, and widening it is a decision about what an
  anonymous caller may see.
- **No structured logging.** Plain text, so logs are greppable but not queryable, and there is
  no request correlation id except on 500s (`incidentId`).
- ~~**No CI.**~~ **Built.** `.github/workflows/backend.yml` runs `clean verify` on every push
  and on pull requests into `main`. See `docs/test-config-decisions.md`.
- **The MinIO dev image is an unpinned moving tag.** `chainguard/minio:latest`, because
  `minio/minio` is no longer anonymously pullable and Chainguard's free tier publishes no
  version tags. Two developers can get different builds and CI can break with no commit behind
  it. Pin by digest or swap to LocalStack; neither changes application code. Detail in
  `docs/image-decisions.md`.

---

## Decisions still open

Nothing below is blocking. Each is a judgement call worth making deliberately rather than by
default.

1. ~~**Price history.**~~ **Decided and built** — it was the only item here that got more
   expensive with every day of delay, which is why it went first. `V11`.
2. **Total result count.** Search returns no total, because counting an arbitrary filter
   combination is a second full pass on every page. If the design calls for *"1 234 résultats"*
   the answer is a cached approximate count or a search engine, not `COUNT(*)` on the hot path.
   It needs planning, not implementing.
3. **Cross-currency price sorting.** Sorting is on `price_amount` across currencies, which is
   meaningless if two ever appear in one result set. Correct while only EUR markets are
   enabled. The real fix is a normalised price column maintained at write time, which needs an
   exchange-rate source.
4. **Where rate limiting ultimately belongs.** The per-IP limit is in the application and costs
   one database upsert per public read. Fine at MVP volume; a reverse proxy or CDN is the right
   long-term home. The key is now the IPv6 /64 rather than the full address, which closes the
   worst hole, but `X-Forwarded-For` is still trusted — so a deployment without a proxy that
   overwrites it has no effective limit at all. See `docs/reference-decisions.md`.
5. **Enum display labels.** The API publishes enum *values* at `/api/v1/reference/enums` and no
   labels, so the set cannot drift but the French text lives in the Angular translation files.
   The alternative — a translation table per enum, as `vehicle_feature_translation` does for
   equipment — moves that text into a database that is not where anyone wants to edit copy.
   Revisit only if a third client appears, or if non-developers need to change the wording.
   **Decided:** values server-side, labels in Angular. No translation tables for enums.
6. ~~**Whether `src/test/resources/application.yml` should be renamed.**~~ **Done.** It is
   now `application-test.yml`, so the suite runs against the production configuration with
   the test profile layered over it. Nothing broke, but the suite had not been reaching
   `open-in-view: false`, `forward-headers-strategy`, Jackson's null handling or Hibernate's
   UTC time zone. Written up in `docs/test-config-decisions.md`.

---

## Frontend gaps opened by the scaffold

The first frontend session built the API layer and the plumbing; these were created or
surfaced by it and are not done.

| Gap | Why it matters |
|---|---|
| **No Content-Security-Policy** | The `localStorage` token decision rests on there being one. The refresh token is a 90-day credential readable by any script on the origin, so XSS is a durable account takeover. It belongs in the edge/proxy configuration, not in the bundle. |
| **Validation failures carry no machine-readable constraint** | The API returns `{field, message}` where `message` is untranslated developer text whose wording is explicitly not a contract — so a client can neither show it nor match on it, and knows only *which* field is wrong, never *why*. A `constraint` code per violation (`{field: "email", constraint: "EMAIL"}`) would let the client say exactly what is wrong in French. Worth doing when the forms are built. |
| **`ImageVariantType` is not in the spec** | It is a map key in `ImageResponse.variants`, and no generator can constrain object keys to an enum, so `variants` is `{ [key: string]: VariantView }`. The clean fix is a response-shape change — `variants` as a list of `{type, url, width, height}` — which is a decision for when the gallery is built. |
| **No global error UI** | `ErrorMessageService` produces the French sentence; nothing displays it yet. A toast or an inline banner is a screen decision. |
| **No HTTP retry or offline queue** | The client distinguishes a transport failure from a server error and says so, and does nothing else about it. |
| **No path filter on a docs-only change** | Both CI workflows are path-filtered to their own trees, so a change to `docs/` or the root `README.md` runs neither job. That is intended, but it means a repository-wide required status check would never be satisfied by such a push if branch protection is added later. |
