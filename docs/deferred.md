# What is deliberately not built

A register of everything knowingly left out, so none of it gets lost between sessions or
rediscovered as a surprise. Each entry says **what is missing**, **what it costs today**,
and **what already exists to build on** — because the expensive part of resuming work is
remembering which half of it is already done.

Refreshed when the MVP became feature-complete: accounts, listings, photos, search, the
listing page, the seller flow and admin moderation are all built, on both sides. Entries
that were closed since the last version are listed at the end rather than deleted.

This file is the *inventory*. How much each gap matters, and which ones are risks rather
than omissions, is `docs/weaknesses.md`.

Four headings:

- **Data we do not have yet** — the code paths exist, the rows do not.
- **Scope the brief put out of the MVP** — not built, not scaffolded, not blocked.
- **Work the MVP itself still needs** — the gaps in what is supposedly finished.
- **Decisions still open.**

---

## 1. Data we do not have yet

### The vehicle catalogue (make / model / trim)

Three tables, **empty in any database that has not run the demo seed**. `vehicle_make`,
`vehicle_model` and `vehicle_trim` are modelled, constrained and indexed; no real rows have
been sourced. The demo seed creates ten makes and their models, records them in its
ledger, and removes them again — they are fixtures, not a catalogue.

**What exists.** Listings may reference the catalogue **or** carry free text — an XOR CHECK
in `V4` enforces exactly one, and composite foreign keys stop a model being paired with
the wrong make. The API refuses a make id with a typed model (and the reverse). The
seller's form handles all of it: catalogue dropdowns, "Autre" opening a text field, and a
fully typed fallback when `GET /reference/makes` returns nothing. Search accepts a make id
or a make name.

**What it costs today.**

- With an empty catalogue every seller types the make, so `BMW`, `bmw` and `B.M.W.` are
  three manufacturers, and the search's make filter has no options to offer.
- **A make or model given as text is filtered by a scan, not an index**
  (`COALESCE(mk.name, l.make_text)`). Measured at 20 000 rows: 16 000 removed by filter.
- Whatever is typed before the catalogue lands needs reconciling against it afterwards.
  `listing.catalog_gap_resolved_at` exists to mark the ones that have been.

**To finish it.** A data source (a commercial feed, or the EU type-approval dataset), an
import job, and a way to run it. No application code changes.

### The gazetteer (postal codes, cities, coordinates)

`geo_place` is modelled and empty.

**What exists.** `V7` made the place reference and the coordinates nullable and added
`postal_code` and `city`, with constraints requiring *some* location and a complete
coordinate pair. The bounding-box filter is implemented and tested. `GeoPlace.normalize`
exists for accent folding. The seller's form asks for a postal code and a city as text.

**What it costs today.**

- **No radius search.** "Within 50 km" cannot be answered; the bounding box matches
  nothing until listings carry coordinates.
- The search's location filter compares what was typed, so `Saint-Étienne` and
  `Saint-Etienne` are different places, and a postal code is not validated against a city.

**To finish it.** For France the national address base is open data. Load it, match
existing listings by postal code, backfill coordinates, switch the filter to the box, and
turn the form's two text fields into a lookup.

---

## 2. Scope the brief put out of the MVP

Not built and **not scaffolded** — no dead tables, no unused endpoints.

| Feature | State, and where it attaches |
|---|---|
| **Messaging** (buyer ↔ seller) | The listing page has a **disabled contact button that says why**. A conversation references a listing and two users; listings are soft-deleted, so a thread never dangles. Needs its own decisions: notifications, abuse reporting, whether a phone number is ever shown. Until it exists a buyer cannot reach a seller at all — see `weaknesses.md` W1. |
| **Favourites / saved searches** | A favourite is a user–listing pair. A saved search is the search page's own query string, which is already a stable, tested contract in both directions (`search-query.ts`). |
| **Subscriptions and payments** | `pro_profile` exists; `app_user.active_listing_limit` overrides the per-role cap per account, which is the hook a paid tier needs. Dealers are uncapped today — which is also a risk, see `weaknesses.md` R2. |
| **AI assistant, price estimation, AutoX Score** | Read-only consumers of listing data. `sold_at` and `listing_price_history` are being retained for them and are exposed by no endpoint. |
| **Seller verification, vehicle inspection** | `email_verified` and `phone_verified` exist and are never set. `pro_profile` holds the company name and registration number, unverified. |
| **Elasticsearch** | Search is one repository behind one service, in SQL, with keyset pagination and a capped count. Swap it when relevance ranking, typo tolerance or facet counts become requirements. |

---

## 3. Work the MVP itself still needs

### Around moderation

The decisions are built: queue, approve, reject, takedown, reinstate, the trail, and a
return to the queue when an approved listing's content is edited. What a moderation
*system* needs beyond them is not:

- **Account suspension.** `UserStatus.SUSPENDED` blocks sign-in and nothing sets it; the
  search's visibility rule does not look at the seller's status either. Needs an endpoint,
  a screen, and one more condition in `ListingSearchRepository.VISIBLE`.
- **Reporting by users.** No endpoint, no page. The footer's "Signaler une annonce" entry
  is declared and hidden until its route exists.
- **User administration.** `GET /api/v1/admin/users` exists and no screen uses it.
- **Telling the seller.** A takedown's reason is shown on the seller's dashboard
  (`removalReason`); no message is sent.
- **What changed.** A listing back in the queue after an edit carries
  `editedSinceReview: true`. There is no diff: the previous content is not kept. Recording
  it would need a revision table.
- **Pre-publication review.** `PENDING_REVIEW` is reserved in the status constraint and has
  no transitions, by design: the table promises nothing the API cannot do. Turning it on —
  for everyone, or for an account's first listing — is a product decision.
- **A record of re-review in the trail.** The trail records moderators' decisions only;
  `moderation_action.admin_id` is NOT NULL and the action check lists four values. A
  system-written "back in queue after edit" line needs a migration.

### Account lifecycle

| | |
|---|---|
| Password change, reset, session list and revoke | Built, both sides. |
| **A real mail provider** | **Missing.** `EmailSender` has one implementation, which logs the message — reset link included. One bean and one property; templates and their French go with it (bodies are English, and the recipient's locale already travels on every `EmailMessage`). |
| **Email verification** | **Missing.** The column exists and is never set. |
| **Closing an account, exporting its data** | **Missing.** `UserStatus.DELETED` exists and nothing sets it. |
| **Terms and privacy at registration** | **Missing**, with the pages themselves. |

### The seller's form

Covers make, model, version, year, mileage, fuel, gearbox, body, condition, title, price,
negotiable, description, postal code, city, and photos. **Not in the form, though the API
accepts them and the listing page displays them:** first-registration month, power,
displacement, drivetrain, doors, seats, colour, interior, previous owners, service history,
warranty, inspection date, CO₂, emission class, VAT, equipment, VIN.

Two rough edges in the dashboard: a listing a moderator removed has no buttons (the API
would accept the seller's delete), and the editor opens for a listing that can no longer
be edited and says so only when a save is refused.

### Screens and qualities the web app does not have

- **A mobile design.** Built desktop-first at 1440px; phones are held to "no horizontal
  overflow", which is checked on every screen and is all that is checked.
- **Server rendering, meta tags, a sitemap.** A client-rendered SPA: listing pages have no
  description or Open Graph tags and are opaque to crawlers and link previews.
- **Legal pages.** Legal notice, terms, privacy policy, cookies: declared in the footer's
  table and omitted until they exist.
- **Content pages.** About, help, advice for buyers and sellers: same.
- **A second language.** Template text carries `i18n` markers; text built in TypeScript
  does not, and nothing is extracted.
- **Error tracking and analytics.** Neither.
- **Retry or offline handling.** A transport failure is told apart from a server error and
  reported; nothing is retried.

### Content safety on uploads

No virus scanning, no image moderation, no hash-matching. EXIF including GPS is stripped,
so the privacy side is handled; the content side is not, and photos are public before any
review.

### Image formats and delivery

JPEG and PNG only, narrower than the database CHECK allows; WebP needs a decoder plugin.
Read URLs are pre-signed per response and expire in six hours unless
`AUTOX_S3_PUBLIC_BASE_URL` points at a CDN or public origin.

### Operations

- **No browser-level tests.** Every flow was driven through a real browser against a real
  API while it was built, by scripts that were not committed.
- **No metrics, no structured logs, no request correlation id** except `incidentId` on a
  500. Actuator exposes `health` only, on purpose.
- **Spring Boot 4.** Two Spring Framework 6.2 advisories have no 6.2 fix
  (`backend/.trivyignore`); the move to Boot 4 / Framework 7 is the only remedy.
- **No backup or retention story** for the database or the bucket.
- **No load test.** Search plans are asserted at 20 000 rows.

---

## 4. Decisions still open

Nothing here blocks building; each is worth deciding rather than defaulting.

1. **Should a dealer be capped until verified?** Today a self-declared dealer is uncapped
   and shown as "Professionnel". See `weaknesses.md` R2.
2. **Should an account's first listing wait for review?** The schema is ready
   (`PENDING_REVIEW`); the product decision is not made.
3. **What counts as a content change for re-review.** As built: every field a buyer reads
   except the price, its negotiable flag and the VAT flag — plus adding or removing a
   photo, but not reordering. The instruction named title, description and photos;
   vehicle facts and location were included because changing the mileage or the model is
   the same hole. Narrowing it is one method, `ListingService.contentOf`.
4. **Where rate limiting belongs.** In the application today, one database upsert per
   public read. A proxy or CDN is the long-term home.
5. **Cross-currency price sorting.** Sorting is on `price_amount`, meaningless across
   currencies. Correct while only EUR markets are enabled.
6. **`ImageResponse.variants` as a map.** No generator can type a map's keys as an enum,
   so the client reads `variants['THUMBNAIL']` by string. A list of `{type, url, …}` would
   fix it and is a breaking response change.
7. **A machine-readable constraint on validation failures.** `{field, message}` today,
   where `message` is developer text that is never shown. A `constraint` code would let
   the client say *why* in French when its own rules and the server's disagree.
8. **Whether a docs-only push should run anything.** Both workflows are path-filtered, so
   a change under `docs/` runs neither — which a required status check would never see
   satisfied.

---

## Closed since the last version

| Was listed as | Now |
|---|---|
| Admin moderation endpoints — "the largest gap" | Built, with the screen. `REMOVED` is no longer terminal in the transition table: a moderator reinstates; a seller cannot. |
| The seller-delete / admin-takedown split | Built as agreed: delete stamps `deleted_at` (permanent), takedown sets `REMOVED` alone (reversible). |
| Total result count | Built: `GET /api/v1/listings/count`, a separate call, capped at 10 000. |
| No global error UI | Every screen shows failures inline, in French, keyed on the error code. |
| Enum display labels | Decided: values from the API, labels in Angular as exhaustive `Record`s. |
| No screens | Search, listing page, auth, account, seller flow, moderation. |
| Health endpoint, CI workflows, test-profile layering, price history | Built earlier; unchanged. |
| CI never run | Pushed; both workflows pass. |
| No security headers | A Content-Security-Policy and the usual headers, written into the built site and sent by the API. |
| No rate limit on registration | Five attempts an hour per address. |
| Unpinned MinIO image | Pinned by digest. |
| No dependency scanning | Trivy on the API image, `npm audit` on production dependencies, Dependabot. |
| The throttles trust the first value of `X-Forwarded-For` | Tomcat's RemoteIpValve, reading from the right; `True-Client-IP` on Render. |
| No container image | `backend/Dockerfile`, sized for 512 MB; `render.yaml` for the service. |
