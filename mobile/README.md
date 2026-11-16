# AutoX — Android app

The buyer side of AutoX as a Flutter app: search, listings, dealers, favourites, saved
searches and messages, in French, with the site's look. Plan, decisions and milestones:
[docs/mobile-plan.md](../docs/mobile-plan.md).

**Status: milestone 6 (release).** Signed APKs on GitHub Releases from `mobile-v*` tags; release
builds speak HTTPS only and log nothing. See "Publishing a release" below.

**Milestone 5 (contacting sellers and reporting).** "Contacter le vendeur" on listings and
dealer pages, the Messages tab (inbox, conversations, replies, unread badge), reporting a listing
or a message. Details and the backend's rules: docs/mobile-plan.md, section 9e.

**Milestone 4 (sign-in and accounts).** Sign-in, registration (private accounts),
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

## Publishing a release

A tag `mobile-v<major>.<minor>.<patch>` runs the `release` job of `.github/workflows/mobile.yml`
after the checks: one universal APK, signed with the release key, published with its `.sha256`
on a GitHub Release named after the tag. Latest:
<https://github.com/YassineBalbali/autox/releases/latest>.

**Version rule** (`tool/release_version.mjs`): `mobile-v1.2.3` gives versionName `1.2.3` and
versionCode `1 × 10000 + 2 × 100 + 3 = 10203`. Minor and patch stay below 100; each release needs a
higher tag than the last, or phones refuse the update. Local builds keep `pubspec.yaml`'s version.

### Once: the keystore

Created on your machine and **never put in the repository** (`*.jks`, `*.keystore` and
`key.properties` are git-ignored). PowerShell, from any folder. `keytool` comes with the JDK; if
it is not on the PATH, use `& "$env:JAVA_HOME\bin\keytool.exe"`.

```powershell
# 1. Create it, outside the repository. keytool asks for the password and your name; type them
#    there, not on the command line (it would stay in the shell history). PKCS12 uses one
#    password for the store and the key.
New-Item -ItemType Directory -Force C:\Users\belbe\keys | Out-Null
keytool -genkeypair -v -keystore C:\Users\belbe\keys\autox-release.jks -storetype PKCS12 `
  -alias autox -keyalg RSA -keysize 4096 -validity 10000

# 2. Its certificate's SHA-256 fingerprint (public, not a secret): the "SHA256:" line.
keytool -list -v -keystore C:\Users\belbe\keys\autox-release.jks -alias autox

# 3. Base64 for GitHub, into a file (nothing printed); copy its content into the secret, then
#    delete the file.
[IO.File]::WriteAllText("C:\Users\belbe\keys\autox-release.jks.base64",
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\belbe\keys\autox-release.jks")))
Get-Content -Raw C:\Users\belbe\keys\autox-release.jks.base64 | Set-Clipboard
Remove-Item C:\Users\belbe\keys\autox-release.jks.base64
```

**Back it up now.** Copy `autox-release.jks` to a USB drive kept offline, and put the password in a
password manager. **If the keystore or its password is lost, no update can ever be signed with
the same key**: Android installs an update only when it is signed by the same certificate, so
every user would have to uninstall the app, losing what it keeps on the phone, and install a new
one.

### Once: GitHub settings

Repository → Settings → Secrets and variables → Actions.

| Name | Tab | Value |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Secrets | The base64 from step 3 |
| `ANDROID_KEYSTORE_PASSWORD` | Secrets | The keystore's password |
| `ANDROID_KEY_ALIAS` | Secrets | `autox` |
| `ANDROID_KEY_PASSWORD` | Secrets | The same password (PKCS12) |
| `AUTOX_API_URL` | Variables | The API, `https://…` (e.g. `https://autox-api-kyuo.onrender.com`) |
| `AUTOX_SITE_URL` | Variables | The site, `https://…` (e.g. `https://autox-demo.vercel.app`) |
| `ANDROID_CERT_SHA256` | Variables | The fingerprint from step 2, colons or not |

The job refuses to publish an APK whose signer is not `ANDROID_CERT_SHA256` (checked with
`apksigner verify --print-certs`), so a wrong keystore in the secret fails the run instead of
shipping an APK that cannot update the installed app.

**Signing locally (optional):** `android/key.properties`, git-ignored, with `storeFile=` (absolute
path), `storePassword=`, `keyAlias=autox`, `keyPassword=`. Without it, `flutter build apk --release`
signs with the debug key and still works.

### Each release

```powershell
# 1. Dependencies: move the pins in pubspec.yaml that need it, run the checks, commit.
flutter pub outdated

# 2. Tag and push the tag (main already pushed and green).
git tag mobile-v1.0.0
git push origin mobile-v1.0.0
```

### Installing it on a phone

1. On the phone, open the release page, download `autox-1.0.0.apk`, open it.
2. Android asks to allow the browser (or Files) to **install unknown apps**: allow it for that
   app, go back, install. Play Protect may warn about an app it does not know.
3. To check the file first, on the PC:

```powershell
(Get-FileHash .\autox-1.0.0.apk -Algorithm SHA256).Hash -eq (Get-Content .\autox-1.0.0.apk.sha256).Split(' ')[0]
# True: it is the file the release job built. Then: adb install .\autox-1.0.0.apk
```

## Layout

```
tool/generate_api.mjs   generates packages/autox_api from ../frontend/openapi.json, and
                        lib/core/labels/labels.g.dart from ../frontend/labels.fr.json
tool/release_version.mjs  mobile-v1.2.3 → versionName and versionCode, for the release job
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
