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

1. **Words → one JSON file**, `shared/fr/labels.json` (enum labels + error messages). Angular
   imports it; Dart gets a generated `labels.g.dart` from it. Both sides keep today's guarantee
   that a new enum value cannot ship without French, as a **test** on each side ("every value of
   every generated enum has a label") instead of a `Record` type. This touches the web, once, in
   milestone 2.
2. **Formulas → duplicated, pinned by shared test vectors.** `shared/fixtures/financing.json`
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
      labels/    (labels.g.dart from shared/fr/labels.json)
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

- **Triggers:** push and PR on `mobile/**`, `shared/**`, `frontend/openapi.json`, the workflow;
  weekly schedule; and tags `mobile-v*`.
- **Job `check`** (every trigger): Flutter 3.50.0 pinned, Java for the generator, generate the
  client, `dart format --set-exit-if-changed`, `flutter analyze`, `flutter test`.
- **Job `release`** (tags `mobile-v*` only, after `check`; the only job with
  `contents: write`): decode the keystore from a secret into the runner's temp dir, `flutter
  build apk --release` with the production API URL, SHA-256 the APK, create the GitHub Release
  with the APK and its checksum attached. The keystore file is deleted at the end of the job.
- The frontend workflow gains `shared/**` in its paths, since the web will read the labels and
  fixtures too.

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
| M2 Search | Search screen, full filter sheet, sort, cursor infinite scroll, live count, typeahead, natural-language search with its summary, /neuf, recent searches. `shared/fr/labels.json` created and the web switched to it |
| M3 Listing and dealers | Listing page with gallery, facts, Crit'Air, price badge, leasing, damage notice, financing simulator (shared vectors), similar listings; dealers directory and dealer pages |
| M4 Account | Sign-in, registration, forgotten password, verification notice and resend, secure storage, refresh interceptor, sessions, sign-out, French error messages |
| M5 Buyer features | Favourites on the device, saved searches and alerts, messages with foreground polling and unread badge, contact a seller or a dealer |
| M6 Release | Signing, `release` job, first GitHub Release, README link, app icon, cold-start and offline states, accessibility pass (TalkBack labels, 48 dp targets, text scaling) |
| Later | App Links for e-mail links, push notifications (FCM + backend), synced favourites, `required` in the spec, iOS, Play Store |

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
