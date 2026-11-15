# AutoX — Android app

The buyer side of AutoX as a Flutter app: search, listings, dealers, favourites, saved
searches and messages, in French, with the site's look. Plan, decisions and milestones:
[docs/mobile-plan.md](../docs/mobile-plan.md).

**Status: milestone 4 (sign-in and accounts).** Sign-in, registration (private accounts),
forgotten password, the account (verification, "Mes données", deletion, sign-out), sessions kept
between launches. Details and the backend's contract: docs/mobile-plan.md, section 9d.

**Milestone 3 (listings and dealers).** The search as the site has it (milestone 2),
and now the listing page — photos full screen, key facts, Crit'Air, the market verdict and
what it rests on, leasing terms, damage, specification, equipment, the financing simulator,
the seller, similar listings — and the dealers' directory and pages, with their listings,
phone and website. Contacting a seller, favourites, sign-in and messages are milestones 4
and 5.

## Requirements

- Flutter **3.50.0** exactly (`C:\src\flutter` on the development PC; CI pins the same).
- Java 21 (the API client generator), Node 22+ (the script that runs it).
- The Android SDK and an emulator (`Pixel_9`, API 35).
- The backend running locally on port 8081: see [docs/development.md](../docs/development.md).

## Run it on the emulator

```bash
# 1. The backend, from the repository root (database and MinIO first):
docker compose up -d
cd backend && mvn spring-boot:run        # API on http://localhost:8081

# 2. The emulator (or start Pixel_9 from Android Studio's Device Manager):
emulator -avd Pixel_9                    # comes up as emulator-5554

# 3. The app, from mobile/:
node tool/generate_api.mjs               # once, and whenever frontend/openapi.json or labels.fr.json changes
flutter run -d emulator-5554
```

**Photos, locally:** the local backend hands out image URLs on `http://localhost:9000` (MinIO),
and on the emulator `localhost` is the emulator itself. Forward the port once per emulator boot,
or the rows show their placeholder:

```bash
adb -s emulator-5554 reverse tcp:9000 tcp:9000
```

Production URLs point at the real storage host and need nothing of the kind.

A debug build talks to `http://10.0.2.2:8081`, which is this PC as the emulator sees it.
Another address: `flutter run --dart-define=AUTOX_API_BASE_URL=https://…`. On a physical
phone over USB, `adb reverse tcp:8081 tcp:8081` and
`--dart-define=AUTOX_API_BASE_URL=http://localhost:8081`.

A release build has no default addresses and refuses to start without `https://` ones:
`--dart-define=AUTOX_API_BASE_URL=…` and `--dart-define=AUTOX_SITE_URL=…` (the site, for the
legal pages). A debug build opens the site at `http://10.0.2.2:4200`, the web
app's dev server.

**E-mails, locally:** the backend writes verification and reset e-mails to its own log (mail
provider `log`). Copy the link's `?token=…` onto `http://localhost:4200/verifier-email` in a
browser, with the web app's dev server running, to verify an address.

## Checks

The same as CI (`.github/workflows/mobile.yml`):

```bash
node tool/generate_api.mjs
dart format --set-exit-if-changed lib test
flutter analyze --fatal-infos
flutter test
```

## Layout

```
tool/generate_api.mjs   generates packages/autox_api from ../frontend/openapi.json, and
                        lib/core/labels/labels.g.dart from ../frontend/labels.fr.json
packages/autox_api/     the generated client (git-ignored, never edited)
lib/
  app.dart main.dart
  core/api/             dio, the hand-written SearchApi, failures in French
  core/config/          AUTOX_API_BASE_URL
  core/theme/           the site's tokens (checked against _tokens.scss) and the theme
  core/routing/         go_router, the four tabs
  core/format/          prices, numbers, listing facts (checked against the web's vectors)
  core/labels/          the French words, generated (git-ignored)
  core/reference/       the catalogue and the API's vocabularies
  core/widgets/
  features/search/      the search: its rules (search_query.dart), state, screens
  features/listing/     the listing page, its rules (listing_text.dart) and parts; the result row
  features/dealers/     the directory and a dealer's page
test/                   mirrors lib/; test/support/fake_http.dart fakes the API;
                        test/shared_fixtures_test.dart runs ../frontend/fixtures/
```

## Things worth knowing

- **One lock file.** `pubspec.yaml` pins every package exactly and lists the generated client
  as a pub workspace member; `pubspec.lock` is committed and decides all versions, the
  client's included. Hence the generator must run before `flutter pub get` on a fresh clone.
- **The site's rules, the web's test vectors.** How a search becomes API criteria and URL
  parameters, how filters read as chips, how a price or a place is written: ported from the web,
  and checked against `frontend/fixtures/*.json`, which the web's own tests run too. Change a
  rule on the web, regenerate the expected values there, then make the app agree.
- **The French words are the web's.** `frontend/labels.fr.json` is read by the site and
  generated into Dart here; a value missing from it, or one the API no longer has, does not
  compile on either side.
- **Search criteria are encoded by hand** (`lib/core/api/search_api.dart`). The generated
  Dart client cannot send the spec's exploded query object; every other call is generated.
- **Tolerant parsing.** An unknown field is ignored and an unknown enum value parses as
  `unknownDefaultOpenApi`, so a backend release never breaks installed apps.
- **No backups.** `allowBackup` is off: the refresh token (milestone 4) must not be restored
  onto another device.
- **Dependencies are updated by hand** (Dependabot cannot resolve the workspace). **Before each
  release, run `flutter pub outdated`** and move the pins in `pubspec.yaml` that need it.
