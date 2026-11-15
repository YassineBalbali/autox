# Mobile app (Flutter, Android first) — investigation and plan

What the backend already offers a native client, what it lacks, and how the app is built.
The decisions taken on this plan are in section 10.

Toolchain: Flutter 3.50.0 (Dart 3.14.0) in `C:\src\flutter`, Java 21, Android SDK 36.1,
emulator `Pixel_9` (API 35).

---

## 1. Authentication

### How the web does it

- `POST /auth/login` and `/auth/register` return a token pair in the **body**: a 15-minute
  HS256 access token and a 90-day random refresh token, rotated on every use.
- The web keeps both in `localStorage` (`core/auth/token-store.ts`) and sends
  `Authorization: Bearer …`. On a 401 it calls `/auth/refresh` **once**, shared by every
  request waiting on it, and replays (`auth.interceptor.ts`, `auth.service.ts`).
- No cookies, no server session, CSRF disabled (`SecurityConfig`: stateless, header-based,
  written "so that the web app and a native app share one contract").
- Logout is `POST /auth/logout` with the refresh token; `GET/DELETE /me/sessions` lists and
  ends sessions, identified to the client by its `deviceLabel`.

### What the app needs: no backend change

The contract is already the one a native client needs. The app will:

- keep the **refresh token in `flutter_secure_storage`** (Android Keystore-backed) and the
  access token **in memory only**;
- run **one refresh at a time**. This is a correctness rule, not an optimisation: presenting a
  rotated token twice is treated as theft and revokes the whole token family, signing the user
  out everywhere. One `Completer` shared by a dio `QueuedInterceptor`, with a test that three
  concurrent 401s produce exactly one `/auth/refresh`;
- send `deviceLabel: "Application Android"` (plus the model if we add `device_info_plus`), so
  the sessions screen shows something a person recognises;
- set `android:allowBackup="false"`, so a cloud backup cannot restore a stale refresh token
  onto another phone.

### Nothing else blocks a native client

- CORS: browser-only; `SecurityConfig` says so and scopes it to `/api/**`.
- No code reads `Origin`, `Referer`, `Cookie` or `Sec-*` headers (searched).
- The response security headers (CSP `default-src 'none'`, `no-store`, no-referrer) are ignored
  by an HTTP client.
- Two things to know, not to fix:
  - **Login throttling by IP** (20 free failures, then delays): phones on a carrier share
    addresses behind CGNAT. It only counts failures and decays on time; acceptable.
  - **Render free plan cold start** (~30–60 s). The first call gets a long timeout and a
    "Le serveur démarre…" state with retry, not an error.

### E-mail links

Verification and reset links point at `AUTOX_SITE_URL` (the Vercel site). In v1 they open in
the browser and the web completes them; the app shows "Vérifiez votre adresse" with a resend
button (`POST /me/email/verification`) and re-reads `/me` on resume. Opening those links in the
app (Android App Links: an `assetlinks.json` on the site) is a later milestone.

---

## 2. Endpoints for v1

All public unless marked 🔒 (Bearer).

| Feature | Endpoints |
|---|---|
| Search | `GET /listings` (criteria, `sort`, `cursor`, `size`; `nextCursor`/`hasMore`), `GET /listings/count`, `GET /catalogue/suggestions`, `GET /reference/enums`, `/reference/makes`, `/reference/makes/{id}/models`, `/reference/features`, `/reference/countries` |
| Natural-language search | `GET /search/capabilities`, `POST /search/interpret` → `criteria` + French `summary` |
| Listing | `GET /listings/{id}`, `GET /listings/{id}/similar`; photos are `ImageVariantView` URLs in the response |
| Crit'Air | `critAir` on the listing, `critAirMax` filter. **Computed server-side** (SQL `crit_air_class`); nothing to duplicate but the vignette colours |
| /neuf | No endpoint of its own: the search with `condition=NEW`, plus `GET /listings/makes?condition=NEW`, `browse=true` for the landing page |
| Dealers | `GET /dealers`, `GET /dealers/{slug}`, 🔒 `POST /dealers/{slug}/conversation` |
| Account | `/auth/register`, `/login`, `/refresh`, `/logout`, `/password/forgot`; 🔒 `GET/PATCH /me`, `POST /me/email/verification`, `GET/DELETE /me/sessions` |
| Saved searches | 🔒 `GET/POST /saved-searches`, `PATCH/DELETE /saved-searches/{id}`, `POST /saved-searches/{id}/visit` |
| Messages | 🔒 `GET /conversations`, `/conversations/unread`, `/conversations/{id}`, `POST …/{id}/messages`, `…/{id}/read`, `POST /listings/{id}/conversation`, `POST /messages/{id}/report`. No push: the web polls every minute while visible; the app does the same while in the foreground |
| Report a listing | `POST /listings/{id}/report` (optional for v1, cheap) |

**Favourites have no backend.** The web keeps them in `localStorage` (`shared/listing/favourites.ts`,
200 max, per browser, "nothing is sent to the API"). The app does the same on the device, with
the same semantics (decided: on the phone for v1). Synced favourites would be a new backend
feature (table + 3 endpoints), later if ever.

### What the web computes client-side

| Logic | Where on the web | Size |
|---|---|---|
| French labels for every enum (fuel, gearbox, body, colour, price level, seller role…) | `shared/listing/labels.fr.ts`, `leasing.fr.ts`, `crit-air.ts`, `listing-report.fr.ts` | ~400 lines of maps |
| French text for every API `ErrorCode` | `core/errors/error-messages.fr.ts` | one map |
| Financing formula | `shared/listing/financing.ts` (`simulateCredit`), kept client-side on purpose: "sent nowhere" | ~60 lines |
| Display rules: price format, vehicle name, summary line, leasing price, "financeable" (sale and not damaged), damage notice, seller badge | `price.ts`, `vehicle-name.ts`, `summary.ts`, `leasing-price.ts`, `damage-notice.ts`, `seller-badge.ts` | small pure functions |

The agreed split (`labels.fr.ts`) is "values from the API, words in the client". Keeping it, the
proposal for one source of truth:

1. **Words → one JSON file**, `frontend/labels.fr.json` (enum labels + error messages; see 9b for why not `shared/`). Angular
   imports it; Dart gets a generated `labels.g.dart` from it. Both sides keep today's guarantee
   that a new enum value cannot ship without French, as a **test** on each side ("every value of
   every generated enum has a label") instead of a `Record` type. This touches the web, once, in
   milestone 2.
2. **Formulas → duplicated, pinned by shared test vectors.** `frontend/fixtures/financing.json`
   (inputs → expected cents, including the published 10 000 € / 5 % / 48 months = 230,29 € case
   and the invalid inputs) and the same for price formatting and vehicle names. Vitest and
   `flutter test` both read the file; a change to one implementation without the other fails CI.
   Moving the formula to the server would contradict "computed in the browser, sent nowhere".

---

## 3. Typed Dart client: generate

I ran the repo's own generator (openapi-generator 7.26.0, from `frontend/openapitools.json`)
with `dart-dio` + `json_serializable` on the committed `frontend/openapi.json` (OpenAPI 3.1,
78 paths, 157 schemas). Result:

- **Generates cleanly**: 15 API classes, all models and enums. One harmless warning (an admin
  method named `hide` renamed `callHide`), and "3.1 support is beta".
- **Broken on the three criteria endpoints** (`GET /listings`, `/listings/count`,
  `/listings/makes`): the spec declares `criteria` as one query object, and the generated code
  passes `{'criteria': criteria}` to dio, which would send `criteria=Instance of …`. The
  TypeScript generator explodes it; the Dart one does not.
- **Every response field is nullable** (`String? id`): only 46 of 122 object schemas declare
  `required`. The web lives with the same (`id?: string`).

**Recommendation: generate, with one hand-written piece.**

- Generate models and API classes from the committed snapshot, the same way as the web:
  generated code not committed, regenerated in CI, same generator version.
- Hand-write `SearchApi` for the three criteria endpoints: it takes the generated
  `ListingSearchCriteria`, flattens `toJson()` into repeated query parameters
  (`fuelType=DIESEL&fuelType=PETROL`) and returns the generated page types. Tested against a
  shared fixture of what the web sends.
- Hand-writing all 157 schemas instead would be weeks of drift-prone work for the same result.

Optional backend improvements, **not needed for v1**:
- `@ParameterObject` on the criteria, so the spec lists 35 flat parameters and both generators
  handle them natively. Changes the web's call sites, so not now.
- An `OpenApiCustomizer` marking non-null record components as `required`, giving non-nullable
  Dart models and tighter TypeScript types. Worth doing once the app exists; it is a contract
  tightening, so the web must be rebuilt against it.

---

## 4. v1 scope

Buyer side, French UI, the site's look, Android only.

**In:** home and search (all filters, sort, cursor-paged results, live count), natural-language
search, /neuf, listing page (gallery, key facts, specification, Crit'Air, market price badge,
leasing offer, damage notice, financing simulator, similar listings), dealers directory and
pages, sign-in, registration (private buyers; professionals are sent to the site), forgotten
password (completed on the site), e-mail verification notice and resend, favourites (on the
device), saved searches and alerts, messages (list, thread, send, report, unread badge),
sessions and sign-out.

**Out:** selling and photo upload, professional registration and dealer-page editing, admin,
La Cote valuation, account deletion and data export (link to the site), push notifications,
App Links, iOS, Play Store.

**Look:** colours, spacing and radii transcribed from `frontend/src/styles/_tokens.scss` into a
Dart `AppTokens` + `ThemeData`; Barlow Semi Condensed bundled as asset font files (OFL, as the
web bundles it — no runtime font download); Lucide icons. Dense classifieds layout, following
the web's mobile pass rather than its desktop one.

---

## 5. Stack

Exact versions pinned in `pubspec.yaml` (no `^`) when milestone 1 creates the project, from
pub.dev's current stable at that time; `pubspec.lock` committed; Dependabot's `pub` ecosystem
added with the repo's existing rules (monthly, no majors, one grouped PR).

| Need | Package | Why |
|---|---|---|
| State | `flutter_riverpod` | Mainstream, testable without widgets, overrides make fakes trivial. Used without its code generator |
| Routing | `go_router` | Maintained by the Flutter team; redirects for signed-in-only screens; ready for App Links later |
| HTTP | `dio` | What `dart-dio` generates for; `QueuedInterceptor` fits the single-refresh rule |
| Models | generated client + `json_annotation` / `json_serializable` / `build_runner` | Generator output |
| Secure storage | `flutter_secure_storage` | Keystore-backed; refresh token only |
| Local storage | `shared_preferences` | Favourites, recent searches, view preference |
| Images | `cached_network_image` | Disk cache. Cache key = image id + variant, not the URL, because without a CDN the URLs are pre-signed and change every six hours |
| Formatting | `intl` + `flutter_localizations` | `fr_FR` numbers, prices, dates |
| Links | `url_launcher` | Phone, dealer website, the site's pages |
| Icons | `lucide_icons_flutter` | Same icon set as the web |
| Tests | `flutter_test`, `mocktail` | Fakes for the API layer |
| Lint | `flutter_lints` + `strict-casts`, `strict-inference`, `strict-raw-types`; `dart format --set-exit-if-changed` | Official set, made strict |

Configuration: API base URL by `--dart-define=AUTOX_API_BASE_URL=…`. Debug builds default to
`http://10.0.2.2:8081` (the emulator's view of this PC, port from your `.env`); cleartext HTTP is
allowed **in the debug manifest only**.

---

## 6. Structure

```
mobile/
  pubspec.yaml  pubspec.lock  analysis_options.yaml  README.md
  tool/generate_api.mjs        # openapi.json → packages/autox_api, then build_runner; .mjs per repo habit
  packages/autox_api/          # generated, git-ignored
  lib/
    main.dart  app.dart
    core/
      config/    api/ (dio, auth interceptor, SearchApi, errors)
      auth/      (session notifier, token store)
      theme/     (tokens, theme, fonts)   format/ (price, km, dates)
      labels/    (labels.g.dart from frontend/labels.fr.json)
      widgets/   (buttons, badges, skeletons, empty and error states)
    features/
      home/ search/ listing/ neuf/ dealers/ auth/ favourites/ saved_searches/ messages/ account/
  test/          # mirrors lib/: unit tests for logic, widget tests per screen
  android/       # signing read from key.properties or env; nothing secret committed
shared/
  fr/labels.json
  fixtures/      # financing, formatting, search-query vectors, read by both test suites
```

**Tests.** Unit: auth interceptor (single refresh, replay, failed refresh signs out), token
store, `SearchApi` encoding, financing and formatting vectors, label exhaustiveness, error-code
mapping. Widget: each screen against a fake API — loading, empty, error, content; filter sheet
round-trip; sign-in errors (401, 429 with `Retry-After`); infinite scroll. No golden screenshot
tests (per CLAUDE.md, no screenshots unless asked).

---

## 7. CI — `.github/workflows/mobile.yml`

Same conventions as `frontend.yml`/`backend.yml` (path filters, concurrency, `contents: read`,
actions pinned the same way).

- **Triggers:** push and PR on `mobile/**`, the frontend files the app is built from (`openapi.json`, `openapitools.json`, `labels.fr.json`, `fixtures/`, `_tokens.scss`), the workflow;
  weekly schedule; and tags `mobile-v*`.
- **Job `check`** (every trigger): Flutter 3.50.0 pinned, Java for the generator, generate the
  client, `dart format --set-exit-if-changed`, `flutter analyze`, `flutter test`.
- **Job `release`** (tags `mobile-v*` only, after `check`; the only job with
  `contents: write`): decode the keystore from a secret into the runner's temp dir, `flutter
  build apk --release` with the production API URL, SHA-256 the APK, create the GitHub Release
  with the APK and its checksum attached. The keystore file is deleted at the end of the job.
- The frontend workflow needs no change: the labels and fixtures are under `frontend/`, which it
  already watches.

## 8. Distribution

- One universal signed APK per tag, attached to the GitHub Release `mobile-v1.0.0` etc.
- README: a "Télécharger l'application Android" link to `…/releases/latest` (stable, never
  needs editing), with the "install unknown apps" note and the checksum.
- **No keystore, no password in the repo.** `key.properties`, `*.jks`, `*.keystore` git-ignored.
- **You do, once:** create an upload keystore with `keytool` (I'll give the exact command),
  keep it and its passwords somewhere safe offline — a lost key means existing installs cannot
  be updated — and add four repository secrets: `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, plus a variable
  `AUTOX_API_BASE_URL`.

---

## 9. Milestones

| # | Delivers |
|---|---|
| M1 Skeleton | `mobile/` project, pinned packages, lint, theme and fonts, bottom navigation (Rechercher · Favoris · Messages · Compte), config, generated client + `SearchApi`, CI `check` job. Runs on the emulator against the local backend and shows reference data |
| M2 Search | Search screen, full filter sheet, sort, cursor infinite scroll, live count, typeahead, natural-language search with its summary, /neuf, recent searches. `frontend/labels.fr.json` created and the web switched to it |
| M3 Listing and dealers | Listing page with gallery, facts, Crit'Air, price badge, leasing, damage notice, financing simulator (shared vectors), similar listings; dealers directory and dealer pages |
| M4 Account | Sign-in, registration, forgotten password, verification notice and resend, secure storage, refresh interceptor, sessions, sign-out, French error messages |
| M5 Buyer features | Favourites on the device, saved searches and alerts, messages with foreground polling and unread badge, contact a seller or a dealer |
| M6 Release | Signing, `release` job, first GitHub Release, README link, app icon, cold-start and offline states, accessibility pass (TalkBack labels, 48 dp targets, text scaling) |
| Later | App Links for e-mail links, push notifications (FCM + backend), synced favourites, `required` in the spec, iOS, Play Store |

## 9a. Milestone 1 — done (2026-11-12)

Built as planned, with these differences, all found while building:

- **Material is a package now.** Flutter 3.47 deprecated `package:flutter/material.dart` in
  favour of `material_ui`; the app imports that. Its `GlobalMaterialLocalizations.delegates`
  supplies the French framework strings, so `flutter_localizations` is not a direct dependency.
- **The generated client needed three fixes**, all applied by `tool/generate_api.mjs` on each
  run, none by hand:
  - `skipCopyWith=true`: the generator's `copy_with_extension_gen` does not support the `build`
    5 that the pinned `build_runner` requires. The app does not copy API objects.
  - `disallow_unrecognized_keys` switched **off** in the generated `build.yaml`. On, any field
    the backend adds would make every installed app fail to parse that response. With
    `enumUnknownDefaultCase`, an unknown enum value parses as `unknownDefaultOpenApi`. Both
    are tested.
  - Enum parameter defaults qualified (`= NEWEST` → `= SearchSort.NEWEST`): dart-dio emits
    code that does not compile there.
- **One lock file through a pub workspace.** The generated package joins the app's workspace
  with `any` constraints, so the committed `pubspec.lock` decides its versions too.
- **Repeated-key encoding checked against the real backend**: `fuelType=DIESEL` (49) +
  `fuelType=PETROL` (67) = `fuelType=DIESEL&fuelType=PETROL` (116), on the local demo data.
- **The theme's colours are tested against `_tokens.scss`**: the test reads the site's file,
  so the two cannot drift silently. The mobile workflow runs when that file changes.
- **CI also builds a debug APK**, to catch Android-side breakage (manifests, Gradle, plugins)
  before milestone 6 adds the signed release.

### Dependency updates

Dependabot's `pub` updater is **not** enabled for `mobile/`: the workspace lists the generated
package, which is not in the repository, so Dependabot cannot resolve it. Until that is solved
(by committing the generated client, or by a scheduled job that regenerates and runs
`flutter pub outdated`), updates are by hand: `flutter pub outdated`, edit the pins, `flutter
pub get`. All direct dependencies were the latest stable on 2026-11-12.

## 9b. Milestone 2 — done (2026-11-14)

**The French words moved first, in a commit of their own.** `frontend/labels.fr.json` holds the
words for every enum the site shows, both sort menus, the Crit'Air choices, the error messages
and the field names. The web's modules read it under the same export names and types; a dump of
every map before and after was byte-identical, and all 1 518 web tests passed. Both clients refuse
to compile when a value is missing (a `Record` on the web, an exhaustive `switch` in the Dart that
`tool/generate_api.mjs` writes), and a stale value fails a test on the web and the compile here.
**It lives in `frontend/`, not a top-level `shared/`** as section 2 planned: Vercel builds from
the `frontend` directory and would not see a file outside it.

**The rules are the web's, pinned by shared vectors.** `frontend/fixtures/search-query.json`
(criteria, URL parameters, parsing of hostile links, the chips' words, the filter count) and
`frontend/fixtures/display.json` (prices, numbers, names, places, leasing terms) were produced by
running the web's own functions, then checked by `src/app/shared/fixtures.spec.ts` on the web and
`test/shared_fixtures_test.dart` here. The Dart port passed all of them on its first run. To change
a rule: change the web, regenerate the vectors, make the app agree.

**What the app does now:** the search field with the catalogue's suggestions; "Recherche
intelligente" when the server offers it, with the "Non pris en compte" note; Acheter / Louer /
Neuf; every filter of the site's sidebar, in its order, offering only the values the API lists;
the four sale and five leasing sort orders; cursor-paged results with a live count and removable
chips; /neuf (makes in new stock, latest arrivals, not counted as searches); the last three
searches, stored as the site's URL parameters and read back through the same validation. The
search is in the route (`/recherche/resultats?marque=…`, the site's parameters), which is what
App Links will map onto later.

**The generator now fails rather than half-works** (`tool/generate_api.mjs`, run by CI before
analyze and test): a generator version different from the web's, a spec that does not validate,
any generator warning not on its known list (two today, both read and harmless), a fix that no
longer applies, or a generated client that does not compile.

**Not in this milestone:** tapping a result (the listing page is milestone 3), the photo gallery,
and favourites.

## 9c. Milestone 3 — done (2026-11-14)

**The listing page**, `/annonces/:id` (the site's path), over the tab bar from wherever it is
opened: photos (swiped, counted, full screen with zoom), the title and publication date, the
price or the leasing offer with its legal mentions, the market verdict with the sentence it rests
on, the damage notice, key facts, the estimated Crit'Air in the vignette's colours, description,
specification, equipment by category, the financing simulator (sale, not damaged), the seller with
a link to the dealer's page, similar listings. A listing no longer online says so.

**The dealers**, `/professionnels` and `/professionnels/:slug`: the directory by name, city or
département, sorted by listings or name, page after page; a dealer's page with phone (the
dialler), website (the browser), hours, description and their listings, sale or leasing — the
site's search limited to that seller (`SearchQuery.sellerId`, app-only, not a URL parameter).

**The rules are the web's, again by shared vectors**: `frontend/fixtures/listing.json`, produced
by the web's own functions — specification rows, equipment grouping, registration date, the market
sentence, `simulateCredit` (the published 10 000 € / 5 % / 48 months case, the limits),
`parseAmount`, `parseRate`, the gallery's order and fallbacks, "Membre depuis", dates and plurals.

**The département names moved to `labels.fr.json` first**, in a web-only commit with the same
proof as the other labels (a dump before and after, byte-identical; all web tests passing).

**Accessibility, found on the emulator and fixed across the app:** a `Semantics` wrapper without
`container: true` merges into its parent, so a heading's flag spread to its whole section, and
loose text was read before the headings and fields that precede it on screen. Every wrapper is
now its own node, sections and equipment groups read heading first, and a result row is one
button read whole. Two layout bugs also fixed: the home search field overflowed on a narrow
screen, and a directory page too short to scroll never loaded the next one.

**Not in this milestone:** contacting a seller or a dealer (it needs an account: milestones 4–5),
reporting a listing (the same), and opening the site's links in the app (App Links, later).

## 9d. Milestone 4 — done (2026-11-15)

### What the backend does (read before building; no change was needed)

| | |
|---|---|
| Sign-in | `POST /api/v1/auth/login` {email, password, deviceLabel} → 200 `AuthResponse` |
| Registration | `POST /api/v1/auth/register` → **201 with a token pair: the account is signed in at once** |
| Refresh | `POST /api/v1/auth/refresh` {refreshToken} → 200, a **new** refresh token every time |
| Sign-out | `POST /api/v1/auth/logout` {refreshToken} → 204, idempotent |
| Me | `GET /api/v1/me` (Bearer) → `UserProfileResponse` (emailVerified, emailVerificationPending) |
| Forgotten password | `POST /api/v1/auth/password/forgot` {email} → 202, whatever the address |
| Resend verification | `POST /api/v1/me/email/verification` (Bearer) → 204 |
| Delete account | `POST /api/v1/me/deletion` {password} (Bearer) → 204; every session ended |
| Data export | `GET /api/v1/me/export` (Bearer) → JSON attachment; the site's page is `/mon-compte/donnees` |

`AuthResponse`: accessToken, tokenType, expiresInSeconds, accessTokenExpiresAt, refreshToken,
refreshTokenExpiresAt, user (`UserSummary`). Access token 15 minutes (HS256); refresh token 90 days,
rotated on every use (`rotate-refresh-tokens: true`).

| Case | Answer |
|---|---|
| Wrong credentials (unknown address or wrong password, indistinguishable) | 401 `INVALID_CREDENTIALS` |
| Throttled sign-in | 429 `TOO_MANY_ATTEMPTS`, `Retry-After` and `retryAfterSeconds` |
| Other throttles (registration, reset, resend) | 429 `RATE_LIMITED` |
| Refresh token unknown, expired, or reused (the whole family is then revoked) | 401 `INVALID_REFRESH_TOKEN` |
| Account suspended / closed (on sign-in and on refresh) | 403 `ACCOUNT_SUSPENDED` / `ACCOUNT_CLOSED` |
| Wrong password on deletion | 400 `CURRENT_PASSWORD_INCORRECT` |
| Unverified address | **not a sign-in error**: it may sign in; publishing and messaging answer 403 `EMAIL_NOT_VERIFIED` |

Passwords: 12 to 128 characters, nothing else (server). The web validates the same client-side,
plus Angular's e-mail rule, the confirmation, the display name, names and phone format; those rules
are now `frontend/fixtures/forms.json`, run by both apps.

Local e-mail: `autox.mail.provider` defaults to `log` (no key in `.env`): the verification and
reset messages, links included, are written to the **backend's log**. `AUTOX_SITE_URL` is not set
locally, so the links are relative (`/verifier-email?token=…`): open them on
`http://localhost:4200` with the web app's dev server running.

### Built

- **Token layer** (`lib/core/auth`): refresh token in the Android Keystore only, access token in
  memory only; one refresh at a time by construction (a shared future filled before the first
  `await`); the rotated token stored before any waiting request resumes; one retry per request;
  the `/auth/*` calls on a separate client with no auth interceptor; a refused refresh clears the
  session and says so once — "Votre session a expiré" on a 401, the account's own sentence on a 403
  (suspended, closed), never "expirée" then; unreachable server keeps the token. Logs redact
  `Authorization` and never write the bodies of `/auth/*`, the password change or the deletion.
- **Screens**: sign-in, registration (private accounts), forgotten password, the account (who,
  verification banner with "Renvoyer le lien", "Mes données", deletion with the password, sign-out),
  the Messages tab asking to sign in. The account's places follow the session through a
  `go_router` redirect; at launch the account waits neutrally while a stored session is checked.
- **Cold start**: 90-second timeouts; under any pending button, after 5 s, "Le serveur démarre…".
- **Shared words**: the forms' messages and the error service's sentences moved to
  `labels.fr.json` (web commit, proved by `forms.json`, produced before the move).

### Chosen differently from the brief

- **Registration signs in.** The API answers with a token pair, and the web goes straight to the
  account. The app does the same and shows "Vérifiez votre boîte mail" there, instead of returning
  to the sign-in form (which would mean discarding a valid session and retyping the password).
- **No "unverified e-mail" sign-in error** and no "renvoyer l'e-mail" on the sign-in form: the API
  lets an unverified address sign in. "Renvoyer le lien" is on the account, as on the web.
- **Terms: a notice, not a checkbox** (follow-up, settled): under "Créer mon compte", on the web and
  in the app, "En créant un compte, vous acceptez les CGU et la politique de confidentialité.", both
  terms linking to the site's pages. One key, `notices.registerConsent` in `labels.fr.json`, links
  written `[words](/path)` and split by the same small function on both sides (`forms.json`). The
  API records no consent.
- **"Mes données" in the app** (follow-up): `GET /me/export` on the app's own client (so an expired
  access token goes through the one refresh), written as the site writes it (two-space JSON,
  `autox-mes-donnees-AAAA-MM-JJ.json`) to the app's cache, handed to the share sheet with
  `share_plus` 13.3.1, then deleted. share_plus copies the file into `cache/share_plus` for the
  receiving app and empties that folder at the next share. The generated `ProfileApi.exportData`
  is not used: its serializer cannot read a free-form object. The debug log never writes the
  export's body.
- **Sign-out clears the phone first**, then asks the server to revoke the session: the result is
  the same whatever the server answers, without a 90-second wait on a cold start.
- **The launch wait is the account tab's**, not the whole app's: the search, listings and dealers
  are public and should not wait up to a minute for a server waking up.
- **Template copy is duplicated**, as in milestones 2 and 3 (button labels, headings); everything
  the site words at runtime — error codes, field names, the forms' messages — is shared.

## 10. Decisions (settled 2026-11-12)

1. **Application id: `io.github.yassinebalbali.autox`.** Dart package name `autox`. Fixed for
   good: changing it later makes a different app on every phone.
2. **Shared `labels.json`: yes.** The web must render exactly the same text as today, proven by
   the existing frontend tests, and the switch is its own commit.
3. **Flutter: the install in `C:\src\flutter`, and CI pinned to exactly its version.** `where
   flutter` finds only that one install. It reports **3.50.0** (Dart 3.14.0), and its git
   reflog shows it was cloned on 2026-11-11 at 22:58 directly at 3.50.0 and has not moved since,
   so the 3.47.5 seen earlier did not come from this install. CI pins 3.50.0.
4. **Favourites: on the phone for v1**, as on the web.
5. **Tags: `mobile-v*`.**
