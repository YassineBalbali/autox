# AutoX — Android app

The buyer side of AutoX as a Flutter app: search, listings, dealers, favourites, saved
searches and messages, in French, with the site's look. Plan, decisions and milestones:
[docs/mobile-plan.md](../docs/mobile-plan.md).

**Status: milestone 1 (skeleton).** The four tabs exist; "Rechercher" shows the number of
listings and the catalogue's makes from the API. Everything else is milestones 2 to 6.

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
node tool/generate_api.mjs               # once, and whenever frontend/openapi.json changes
flutter run -d emulator-5554
```

A debug build talks to `http://10.0.2.2:8081`, which is this PC as the emulator sees it.
Another address: `flutter run --dart-define=AUTOX_API_BASE_URL=https://…`. On a physical
phone over USB, `adb reverse tcp:8081 tcp:8081` and
`--dart-define=AUTOX_API_BASE_URL=http://localhost:8081`.

A release build has no default address and refuses to start without an `https://` one.

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
tool/generate_api.mjs   generates packages/autox_api from ../frontend/openapi.json
packages/autox_api/     the generated client (git-ignored, never edited)
lib/
  app.dart main.dart
  core/api/             dio, the hand-written SearchApi, failures in French
  core/config/          AUTOX_API_BASE_URL
  core/theme/           the site's tokens (checked against _tokens.scss) and the theme
  core/routing/         go_router, the four tabs
  core/widgets/ core/format/
  features/<feature>/   one folder per screen family
test/                   mirrors lib/; test/support/fake_http.dart fakes the API
```

## Things worth knowing

- **One lock file.** `pubspec.yaml` pins every package exactly and lists the generated client
  as a pub workspace member; `pubspec.lock` is committed and decides all versions, the
  client's included. Hence the generator must run before `flutter pub get` on a fresh clone.
- **Search criteria are encoded by hand** (`lib/core/api/search_api.dart`). The generated
  Dart client cannot send the spec's exploded query object; every other call is generated.
- **Tolerant parsing.** An unknown field is ignored and an unknown enum value parses as
  `unknownDefaultOpenApi`, so a backend release never breaks installed apps.
- **No backups.** `allowBackup` is off: the refresh token (milestone 4) must not be restored
  onto another device.
