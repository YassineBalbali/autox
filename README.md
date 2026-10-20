# AutoX

**Live demo: [autox-demo.netlify.app](https://autox-demo.netlify.app)**

A used-car marketplace in French, built as a **portfolio project**. The listings, sellers
and prices on the live site are invented — none of those cars is for sale — and accounts
created there are for trying it out, not for real use. Search and filter cars, open a
listing, or register and publish one of your own, photos included.

The first request after a quiet spell can take a few minutes: the API runs on a free
instance that sleeps.

---

A vehicle marketplace — buying and selling cars — built for the French market first and
multi-country from the data model up. Competitors are La Centrale, Leboncoin and Mobile.de.

**The MVP is feature-complete and deployed as a demo.** A visitor can search and filter used
cars and open a listing. A seller can register, create a listing in four steps with photos
uploaded straight to object storage, publish it, and pause, renew, mark as sold or delete
it. An administrator reviews what was published, refuses or takes down a listing with a
reason, and reinstates it. The interface is French and designed for a desktop screen. The
demo runs on Netlify (site), Render (API), Neon (PostgreSQL) and Supabase Storage (photos);
`docs/deployment.md` is how.

**What it is not:** a real marketplace. A buyer cannot contact a seller, there is no real
vehicle catalogue or location data, and no email is ever sent. `docs/weaknesses.md` ranks
all of that, and separates what is merely unfinished from what would be a risk with real
users.

| | |
|---|---|
| **Backend** | Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, S3-compatible object storage |
| **Frontend** | Angular 22, standalone, zoneless, Vitest; API client generated from the OpenAPI spec |
| **Layout** | `backend/` and `frontend/` at the repository root |

---

## The two things to know before reading the code

**It is headless and API-first.** Every rule lives in the backend. The web app is a pure API
consumer, and a native mobile app will later call the *same* endpoints — so no endpoint
assumes a browser, a cookie or a session. The contract is the OpenAPI document the application
serves, not anything the frontend happens to do: the frontend's TypeScript models and HTTP
clients are **generated** from that document, and a backend test fails if the committed copy
falls behind.

**It is multi-country from day one.** Country, currency and locale are in the schema now, not
bolted on later. Prices are integers in the currency's minor unit with an ISO 4217 code beside
them; distances are always kilometres on the wire whatever a market displays. Only France is
enabled today.

---

## Running it from a clean checkout

### You need

- **JDK 21** — `java -version` should say 21. Needed by the frontend too: the API client
  generator runs on the JVM.
- **Maven 3.9+** — or use the wrapper if one is added later
- **Node 24.15+** (or 22.22.3+, or 26+) — what Angular 22 accepts; `frontend/package.json`
  records it in `engines`
- **Docker** with Compose — for PostgreSQL, object storage, and the integration tests

### The start sequence

Four commands, from a clean checkout, in a clean terminal. **Nothing to export.**

```bash
git clone <this repository>
cd autox

cp .env.example .env     # 1. then fill in the two blanks it leaves — see below
docker compose up -d     # 2. PostgreSQL on 5433, MinIO on 9000/9001

cd backend
mvn spring-boot:run      # 3. the API, on AUTOX_PORT — 8081 in .env.example
```

```bash
cd frontend              # 4. in a second terminal
npm ci
npm start                # http://localhost:4200
```

**`.env` is read by both docker compose and the application.** Compose reads it natively;
the application reads it at startup through `DotenvEnvironmentPostProcessor`. That is the
whole reason there is nothing to export: before it existed, `mvn spring-boot:run` inherited
none of the file and died on an unconfigured datasource, a hundred and fifty stack frames
deep, in a message that named neither the file nor the variable.

Precedence is the part worth knowing:

```
system properties  >  real environment variables  >  .env  >  application.yml defaults
```

A real environment variable always wins, so `.env` changes nothing about CI, containers or
production — in those places the file does not exist and the loader is a no-op. And because
`.env` sits above `application.yml`, it behaves like the export it replaces rather than like
a weaker kind of default. Set `AUTOX_PORT=8099` in your shell and it beats the `.env` value;
that is intentional and tested.

Two syntax rules differ from docker compose, and `.env.example` repeats them: whole-line
`#` comments only (a `#` inside a value is part of the value), and no `${VAR}` interpolation.
Avoid `${` inside a password specifically — Spring resolves placeholders inside every
property value, so such a password is unusable.

Flyway applies the migrations on first boot — there is no schema step to run by hand, and
nothing to create but the database the container already makes. Hibernate runs with
`ddl-auto: validate`: it checks the entities against the migrated schema and refuses to
start on drift, so a stale entity fails fast rather than silently.

`npm start` regenerates the API client from `frontend/openapi.json` before serving, so there
is no generation step to remember either. The dev server calls the API at the URL in
`src/environments/environment.development.ts` — port **8081**, which is what
`.env.example` sets `AUTOX_PORT` to. Keep the two in step: with no `AUTOX_PORT` at all the
API listens on 8080 and the web app, calling 8081, shows a connection error on every page.

One more pair has to stay in step: `autox.mail.password-reset-url` is the link in the reset
email, and its path must match the Angular route `/reinitialiser-mot-de-passe`. A mismatch
produces a link that loads the application and renders nothing — no error, on either side.
With `AUTOX_MAIL_PROVIDER=log` the link is written to the backend log, which is what makes
the reset flow walkable locally.

### Demo data

An empty database makes for an empty search page. For local development there is a seed:

```bash
cd backend
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run                            # seed, once
SPRING_PROFILES_ACTIVE=demo AUTOX_DEMO_ACTION=remove mvn spring-boot:run   # take it all out
```

(or put the two variables in `.env`). It creates 60 invented listings — each with three photos, a description and
equipment — eight accounts and a ten-make catalogue, through the same services the API uses — each listing goes through
the real photo upload and the real publish step.

- **Separate.** The code is in `com.autox.demo` and every class is `@Profile("demo")`:
  without the profile it is not loaded, and there is no endpoint for it.
- **Identifiable.** Demo accounts end in `@demo.autox.invalid`; a `demo_seed_ledger` table
  exists if and only if the database holds demo data.
- **Exactly removable.** The ledger lists every row the seed created, and removal deletes
  those rows by id plus their stored photos — never "anything that looks like demo data".
  A make that was already in the catalogue is reused, not recorded, and so not deleted.
- **Not sign-in-able.** The accounts have random passwords nobody is told.

Sixty, because a page of results is 24: it takes more than two pages before "voir plus"
and keyset pagination can be exercised in a browser at all. Fourteen are written by hand
and the rest generated from model templates with a fixed seed, so the set is identical
on every machine. Seeding takes about a minute and a half — each listing is three real
uploads with three resizes apiece.

The photos are drawings generated by the seeder, labelled as demonstration images: no
photograph is committed to the repository. Set `AUTOX_DEMO_PHOTO_DIR` to a folder of JPEGs
named `make-model.jpg` to use real ones.

One thing to know: the reference endpoints are cached by the browser for an hour. After a
remove-and-seed the makes have new ids, so hard-reload the page (Ctrl+Shift+R) or the make
filter will use the old ones.

### If it will not start

The application refuses to start when configuration is missing, and says what is missing by
name:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

The application is not configured.

    AUTOX_JWT_SECRET  is set but empty
                      needs an HS256 signing key of at least 32 bytes — generate one with
                      `openssl rand -base64 48`
                      (sets autox.auth.jwt-secret)

Action:

Edit the .env at the repository root and give the settings above a value, then start again.
```

`is set but empty` is the common one: `.env.example` ships `AUTOX_JWT_SECRET=` blank
deliberately, so a straight copy leaves it that way. If no `.env` was found at all the
message lists the paths it looked in and tells you to create one.

### Editing `.env`

Two values must be filled in; the rest have working defaults.

```bash
# A password of your choosing, used by both the container and the backend.
POSTGRES_PASSWORD=...
AUTOX_DB_PASSWORD=...     # the same value

# An HS256 signing key, at least 32 bytes:
openssl rand -base64 48
AUTOX_JWT_SECRET=...
```

There is deliberately **no default for either secret**, nor for the two object-storage keys.
A default password is a password in version control, and one that works locally is one that
reaches a server eventually — so a missing credential fails startup, naming the variable,
instead of falling back to something weak. `.env` is gitignored; nothing in version control
holds a credential.

The non-secret coordinates *do* have defaults that match docker-compose — `AUTOX_DB_URL`
and `AUTOX_DB_USERNAME` — so the only lines that genuinely need your attention are the
ones above.

### Check it came up

```bash
curl http://localhost:8081/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
#
# For a second or two after the log says "Started AutoxApplication" this answers
# OUT_OF_SERVICE: readiness flips to accepting traffic on ApplicationReadyEvent, which
# fires just after. That is the probe working, not a fault.

curl http://localhost:8081/api/v1/reference/countries
# [{"code":"FR","name":"France","defaultLocale":"fr-FR", ... "currencyMinorUnit":2}]
```

Then open **http://localhost:8081/swagger-ui.html** for the whole API, and
**http://localhost:4200** for the site. With an empty database the search page says there
is nothing for sale — see *Demo data* above.

### An administrator account

There is no endpoint that creates one, on purpose. Register an account in the web app,
then promote it:

```bash
docker exec -i autox-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
UPDATE app_user SET role = 'ADMIN' WHERE email = 'you@example.com';
SQL
```

Sign out and in again — the role travels in the access token — and "Administration"
appears in the account menu. The demo seed's listings are all awaiting review, so the
moderation queue has sixty entries to try it on.

### Ports already in use

Both are common collisions, and both are a variable away from fixed:

- **5432** — a natively installed PostgreSQL wins over Docker's port proxy, and the
  connection silently reaches the *wrong server*. That is why compose maps **5433**. If 5433
  is also taken, change `POSTGRES_PORT` and the port inside `AUTOX_DB_URL` together.
- **8081** (or 8080) — set `AUTOX_PORT`, and `apiBaseUrl` in
  `frontend/src/environments/environment.development.ts` to match.

### Tests

```bash
cd backend
mvn clean verify         # 403 tests, needs Docker running

cd ../frontend
npm run lint
npm test -- --no-watch   # 920 tests, jsdom
npm run test:scripts     # 21 tests of the build scripts: the security policy
npm run build            # the production build, with the policy and headers written in
```

Every backend test is an integration test against a real PostgreSQL and a real S3-compatible store
in Testcontainers. Nothing about authentication, storage or SQL is mocked: a passing test
exercises the code path a phone would. The containers start once and are shared, so the suite
pays that cost a single time (expect roughly five minutes in total).

**Use `clean`.** The tests run against the production `application.yml` with
`application-test.yml` layered on top, and Maven does not remove resources that have been
renamed — so an incremental build can keep loading a stale copy and run the whole suite
against the wrong configuration. `ConfigurationLayeringIntegrationTest` fails loudly if that
happens, with the remedy in the message.

The frontend's tests render components in jsdom against a mocked HTTP backend. **No test
drives a real browser against the real API**; that was done by hand for every screen and
is not automated — `docs/weaknesses.md`, W10.

### CI

Two workflows, both on **every push** to every branch, on pull requests into `main` or
`master`, and **weekly** whatever was pushed — an advisory published about a dependency
nobody touched should not wait for the next commit. Each push run is filtered to the paths
that can change its result.

| Workflow | Watches | Runs |
|---|---|---|
| `.github/workflows/backend.yml` | `backend/**`, `frontend/openapi.json`, itself | `mvn -B clean verify`, then builds the API image from `backend/Dockerfile` and scans it |
| `.github/workflows/frontend.yml` | `frontend/**`, itself | `npm ci`, an audit of production dependencies, generate, lint, the build-script tests, build, test |

**Dependency scanning.** The backend job builds the image Render deploys and scans it — its
Alpine packages and every library in it — with Trivy (run from its image, pinned by digest),
and fails on a HIGH or CRITICAL advisory that has a fixed version. Advisories judged not to
apply are in `backend/.trivyignore`, each with the reason a reviewer can check. The frontend
job runs `npm audit --omit=dev --audit-level=high`: what reaches a browser.
`.github/dependabot.yml` proposes the upgrades, grouped weekly, for Maven, npm, the Actions
themselves, the MinIO image digest and the Dockerfile's base images.

**The MinIO image is pinned by digest**, in `docker-compose.yml` and in the test suite, so
two machines — and CI — run the same build. Dependabot proposes the next digest.

GitHub's Ubuntu runners have a Docker daemon, so Testcontainers needs no extra setup, and
no secrets are involved — the test profile supplies its own signing key and the containers
supply the rest. On failure the Surefire XML reports are uploaded as an artifact, because
the console log alone does not tell you which assertion broke.

**`frontend/openapi.json` is in the *backend* job's paths on purpose.** That file is the API
contract, and `OpenApiSnapshotTest` compares it against the document the application
actually serves — so a snapshot refreshed without the backend change behind it, or a backend
change whose snapshot was not refreshed, fails there.

---

## The API

Served by the running application:

| | |
|---|---|
| **OpenAPI JSON** | `/v3/api-docs` |
| **OpenAPI YAML** | `/v3/api-docs.yaml` |
| **Swagger UI** | `/swagger-ui.html` |
| **Health** | `/actuator/health`, plus `/actuator/health/liveness` and `/readiness` |

All four are public — the specification because a closed one on a public API protects
nothing, and health because a load balancer has no account. Health returns a status and
nothing else, and **it is the only actuator endpoint exposed**: `env`, `configprops`,
`beans`, `metrics`, `loggers` and `heapdump` are all off, and a test asserts it. The
document is generated from the controllers and their validation annotations, so it cannot drift
from the code; the prose comes from the Javadoc already on those methods rather than from a
second copy inside annotations. A test asserts every mapped endpoint appears in it.

### Route map

**The web app** (`frontend/src/app/app.routes.ts`). Paths are French because they are part
of the interface.

| Path | Who | What |
|---|---|---|
| `/` | anyone | Home: search card with a live count, recent searches, make, budget and category shortcuts. An old search link (`/?marque=…`) is forwarded to `/recherche` |
| `/recherche` | anyone | Search results: filters, sort and view in the query string; a heart on each result marks it in this browser only |
| `/annonces/:id` | anyone | One listing |
| `/connexion`, `/inscription` | anyone | Sign in, register (private seller or dealer) |
| `/mot-de-passe-oublie`, `/reinitialiser-mot-de-passe` | anyone | Reset by emailed link |
| `/mon-compte`, `…/mot-de-passe`, `…/sessions` | signed in | Profile, password, devices |
| `/mon-compte/entreprise` | dealer, admin | Company details |
| `/vendre` | signed in | Create a listing (`?etape=`) |
| `/mes-annonces` | signed in | The seller's listings (`?statut=`) |
| `/mes-annonces/:id/modifier` | signed in | Edit a listing (`?etape=`) |
| `/admin` → `/admin/moderation` | admin | Moderation (`?vue=`, `?page=`) |
| `/guide-de-style` | anyone, unlinked | Tokens and shared components, rendered |
| `/acces-refuse`, anything else | anyone | Refused; not found |

A route guard is a courtesy. The API is what refuses.

**The API**, all under `/api/v1`. The OpenAPI document is the authority; this is the map.

| Area | Endpoints | Auth |
|---|---|---|
| Accounts | `POST /auth/register`, `/login`, `/refresh`, `/logout`, `/password/forgot`, `/password/reset` | none |
| The caller | `GET`·`PATCH /me`, `POST /me/password`, `GET /me/sessions`, `DELETE /me/sessions/{id}` | token |
| Public listings | `GET /listings` (search), `GET /listings/count`, `GET /listings/{id}` | none |
| A seller's listings | `POST /listings`, `GET /listings/mine`, `/mine/quota`, `/mine/{id}`, `PATCH`·`DELETE /listings/{id}`, `POST /listings/{id}/publish`·`unpublish`·`renew`·`sold` | token, owner |
| Photos | `POST`·`GET /listings/{id}/images`, `POST …/images/{imageId}/confirm`, `PUT …/images/order`, `DELETE …/images/{imageId}` | token, owner |
| Reference | `GET /reference/countries`, `/enums`, `/makes`, `/makes/{id}/models`, `/features` | none |
| Moderation | `GET /admin/listings`, `/admin/listings/{id}`, `POST /admin/listings/{id}/approve`·`reject`·`remove`·`reinstate` | ADMIN |
| Users | `GET /admin/users` | ADMIN |

### Conventions that apply everywhere

- **Money** is an integer of the currency's minor unit plus an ISO 4217 code.
  `priceAmount: 1850000, priceCurrencyCode: "EUR"` is €18,500.00. The scale comes from
  `/api/v1/reference/countries` — do not assume 2.
- **Errors** are RFC 9457 `application/problem+json` with a stable `code`. **Branch on
  `code`**, never on `detail`: `detail` is English developer text and is not translated.
  Validation failures add a flat `errors` array of `{field, message}`.
- **Enums** serialise as their constant name. The full set is at `/api/v1/reference/enums`.
- **Every list endpoint is paginated**, and there is no unbounded form of any of them. Public
  search is cursor-paginated: pass the previous response's `nextCursor` back as `cursor`.
  There are no page numbers; the total is a separate call, `GET /api/v1/listings/count`,
  capped at 10 000. The seller's and the administrator's lists are page-numbered.
- **Photos never pass through the API.** Request a slot, PUT the bytes straight to object
  storage with the pre-signed URL, then confirm. The server builds the size variants.
- **The API is language-neutral.** It returns codes, never display strings. All user-facing
  French lives in the Angular app.

### Authentication

A 15-minute JWT access token plus a long-lived opaque refresh token (90 days), because a phone
session has to survive months without a re-login. Refresh tokens rotate on every use, and
replaying a rotated one revokes the whole family — the signal that one was stolen.

```
POST /api/v1/auth/register   →  { accessToken, refreshToken, ... }
POST /api/v1/auth/login      →  same shape
POST /api/v1/auth/refresh    →  a new pair; the old refresh token is now dead
POST /api/v1/auth/logout
```

Then `Authorization: Bearer <accessToken>`.

Roles: **PARTICULIER** (private seller, free, capped at 5 live listings), **PROFESSIONNEL**
(dealer, uncapped until subscription tiers exist), **ADMIN**.

### Moderation

A listing goes online when its seller publishes it and is reviewed afterwards. "Awaiting
review" is therefore not a status: it is `status = ACTIVE` with
`moderationStatus = NOT_REVIEWED`. Everything below requires the **ADMIN** role (403
otherwise, 401 without a token).

```
GET  /api/v1/admin/listings?view=PENDING|LIVE|REMOVED   →  a page of listings, each complete
GET  /api/v1/admin/listings/{id}
POST /api/v1/admin/listings/{id}/approve      { note? }              ACTIVE, APPROVED
POST /api/v1/admin/listings/{id}/reject       { reasonCode, note? }  REMOVED, REJECTED
POST /api/v1/admin/listings/{id}/remove       { reasonCode, note? }  REMOVED, REJECTED
POST /api/v1/admin/listings/{id}/reinstate    { note? }              ACTIVE | PAUSED | EXPIRED, APPROVED
```

- **Each listing in the answer carries everything**: the full listing with its photos, the
  seller (email included), `allowedActions`, and the `trail` — who did what, when, why.
- **`reject` is for a listing nobody has reviewed; `remove` takes down any listing that is
  online.** Both need a `reasonCode`; `OTHER` also needs a `note`. The seller is shown the
  reason (`removalReason` on their own listing) and never the note or the moderator.
- **A moderator's removal is reversible; a seller's delete is not.** The first sets
  `REMOVED` alone, the second also stamps `deleted_at`, after which the listing exists for
  no endpoint — including these.
- **`reinstate` returns the listing to where it would be**: online, or `PAUSED` if the
  seller's quota has filled meanwhile, or `EXPIRED` if its window ran out.
- **A seller cannot leave `REMOVED`.** `allowedTransitions` on a seller's own listing is
  empty there, and `publish`/`renew` answer 409 — for an administrator calling those
  endpoints too, so that a reinstatement always goes through the trail.

- **An approval is of the listing as it was.** When the seller later changes its content —
  anything a buyer reads except the price, or adds or removes a photo — the verdict
  returns to `NOT_REVIEWED` and the listing to the queue, still online, marked
  `editedSinceReview`. A price change alone, a reorder of photos, or a save that changes
  nothing does not.

There is no endpoint that creates an administrator; see *An administrator account* above.

### Password and session management

```
POST   /api/v1/auth/password/forgot  →  202, always, whether or not the address is registered
POST   /api/v1/auth/password/reset   →  204; single-use token, 30-minute window
POST   /api/v1/me/password           →  a NEW token pair (see below)
GET    /api/v1/me/sessions           →  the account's live sessions
DELETE /api/v1/me/sessions/{id}      →  204; ends that one
```

Two things a client has to get right:

- **`POST /me/password` ends every session, including the caller's own,** and returns a fresh
  token pair. Store it: the refresh token the client arrived with no longer works. The server
  cannot spare "this session" — an access token carries no reference to the refresh family it
  was issued with.
- **A reset also ends every session**, so the user signs in again on every device.

**There is no mail provider configured.** `autox.mail.provider=log` writes the email — reset
link included — to the application log, which is how the flow is usable locally and is a
credential leak anywhere else. Production needs an `EmailSender` bean and the property set.

---

## Environment variables

Secrets have no defaults, on purpose. Everything else does.

### Required

| Variable | Notes |
|---|---|
| `AUTOX_DB_URL` | e.g. `jdbc:postgresql://localhost:5433/autox` |
| `AUTOX_DB_USERNAME` | |
| `AUTOX_DB_PASSWORD` | |
| `AUTOX_JWT_SECRET` | HS256 key, ≥ 32 bytes. Rotating it invalidates access tokens in flight; refresh tokens are random, not signed, so they survive. |
| `AUTOX_S3_ACCESS_KEY` | |
| `AUTOX_S3_SECRET_KEY` | |

### Object storage

| Variable | Default | Notes |
|---|---|---|
| `AUTOX_S3_ENDPOINT` | — | MinIO locally; a provider's endpoint in production |
| `AUTOX_S3_REGION` | `us-east-1` | |
| `AUTOX_S3_BUCKET` | `autox-images` | |
| `AUTOX_S3_PATH_STYLE` | `false` | `true` for MinIO and any IP-addressed endpoint |
| `AUTOX_S3_CREATE_BUCKET` | `false` | Local convenience. Leave off in production. |
| `AUTOX_S3_PUBLIC_BASE_URL` | empty | A CDN or public-bucket origin. **Empty means read URLs are signed and expire in 6 hours** — correct but uncacheable; see `docs/image-decisions.md`. |

One interface, five methods, no vendor type: MinIO, Supabase Storage, Cloudflare R2, Scaleway,
Backblaze B2 and AWS S3 all speak this protocol, so the provider is configuration rather than
code.

### Transactional email

| Variable | Default | Notes |
|---|---|---|
| `AUTOX_MAIL_PROVIDER` | `log` | **`log` writes the message to the application log instead of sending it.** That makes the password reset flow walkable locally — the link is in the console — and is a credential leak in production, since anyone who can read the log can take over any account. It warns at every startup. A real provider is an `EmailSender` bean conditional on its own value here. |
| `AUTOX_MAIL_FROM` | `no-reply@autox.local` | Unused by the logging sender; required by every real one. |
| `AUTOX_MAIL_FROM_NAME` | `AutoX` | |
| `AUTOX_PASSWORD_RESET_URL` | `http://localhost:4200/reinitialiser-mot-de-passe` | The frontend page that takes the token; the email links to it with `?token=…`. The API cannot guess it — the web app is deployed separately. |

### Optional

| Variable | Default | Notes |
|---|---|---|
| `AUTOX_PORT` | `8080` | `.env.example` sets 8081, which is what the web app's development build calls. |
| `AUTOX_LOG_LEVEL` | `INFO` | Application logging only |
| `AUTOX_DB_POOL_SIZE` | `10` | |
| `AUTOX_DB_POOL_MIN_IDLE` | the pool size | Connections kept open while idle. `0` lets a database that scales to zero sleep |
| `AUTOX_DB_IDLE_TIMEOUT_MS` | `600000` | How long an unused connection is kept, above the minimum |
| `AUTOX_SCHEDULE_LISTING_EXPIRY`, `AUTOX_SCHEDULE_IMAGE_CLEANUP` | `PT10M`, `PT15M` | Intervals of the two background sweeps |
| `AUTOX_SCHEDULE_AUTH_MAINTENANCE` | `0 17 * * * *` | Cron of the token and counter purge |
| `AUTOX_SCHEDULE_STORAGE_CHECK` | `PT12H` | How often the API checks the bucket: reports a storage outage, and keeps a free Supabase project from pausing |
| `AUTOX_CLIENT_IP_HEADER` | `X-Forwarded-For` | Which proxy header carries the client address; `True-Client-IP` on Render. See below |
| `AUTOX_JWT_ISSUER` | `https://api.autox.local` | |
| `AUTOX_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Comma-separated. Browsers only — a native app is not subject to CORS. |
| `AUTOX_LOGIN_THROTTLE_ENABLED` | `true` | Leave on. |
| `AUTOX_PUBLIC_RATE_LIMIT_ENABLED` | `true` | Per-IP limit on the public reads |
| `AUTOX_PUBLIC_RATE_LIMIT_REQUESTS` | `300` | Per minute, per address |
| `AUTOX_REGISTRATION_THROTTLE_ENABLED` | `true` | Leave on. |
| `AUTOX_REGISTRATION_MAX_ATTEMPTS` | `5` | Registration attempts per hour, per address — successful or not |

The compose file also reads `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
`POSTGRES_PORT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_PORT` and
`MINIO_CONSOLE_PORT`. The `POSTGRES_*` and `MINIO_*` values must match their `AUTOX_*`
counterparts, since one pair configures the container and the other the client.

### Building the site for a host

`npm run build` is `ng build` followed by `scripts/build.mjs`, which writes the
Content-Security-Policy into the page and the security headers into `_headers`. The policy
has to name the API and the photo storage, so three variables are read **at build time**:

| Variable | Meaning |
|---|---|
| `AUTOX_API_BASE_URL` | The API's origin. Empty: the site and the API share one. |
| `AUTOX_STORAGE_ORIGINS` | Every origin photos are read from or uploaded to, space-separated. |
| `AUTOX_SITE_URL` | The site's origin: makes the link-preview image absolute, and turns on HSTS. |

With none set, the policy allows the site's own origin only, which is what CI builds. The
build fails if Angular ever emits inline script, which the policy would block in production.
It also writes `_redirects` with `/*  /index.html  200`, Netlify's single-page fallback, so a
shared link to a listing is served the app with a 200 rather than a 404. There is no
`404.html`, on purpose. `netlify.toml` holds the build settings.

### Deploying

`docs/deployment.md` walks through the demo's deployment step by step: Neon for PostgreSQL,
Supabase Storage for photos, Render for the API (`backend/Dockerfile`, `render.yaml`) and
Netlify for the site (`netlify.toml`). It lists every variable, says which service each value comes
from, and covers seeding, the first administrator and the checks to run afterwards.

### Deploying behind a proxy

Every throttle keys on the client address, so who the client is decides whether the limits
hold. `server.forward-headers-strategy=native` hands that to Tomcat's `RemoteIpValve`. It
believes a forwarding header only on a connection from an internal proxy (the private
address ranges by default), and reads it **from the right**, skipping the hops it trusts.
A value the client wrote at the front of `X-Forwarded-For` is therefore never taken for
the client. `AUTOX_CLIENT_IP_HEADER` names the header: `X-Forwarded-For` behind a single
appending proxy, `True-Client-IP` on Render, whose Cloudflare edge would otherwise be every
visitor's address. `ClientAddressIntegrationTest` covers all three cases on a real port,
and `docs/deployment.md` (step 8) has the check to run once deployed.

Both throttles key IPv6 on the **/64 prefix**, not the full address — a single connection is
routinely handed a /64, so keying on the address means an IPv6 caller is never limited at
all. IPv4 is keyed on the address itself. The full address is still recorded per session for
the user's own session list; masking applies only to the throttle key.

---

## Repository map

```
backend/
  src/main/java/com/autox/
    api/          error contract: ApiException, ErrorCode, RFC 9457 handler
    auth/         register, login, refresh, logout; login throttling
      password/     change, forgotten-password reset
    user/         accounts, profiles, refresh tokens, the session list
    listing/      the seller's listings; state machine; quota; price history; photos
                  (listing/image); moderation decisions and their trail
    admin/        the administrator-only controllers: moderation, the user list
    search/       public search and detail; keyset cursors
    reference/    countries and currencies; the reference endpoints
    catalog/      vehicle make/model/trim, equipment vocabulary
    throttle/     the shared counter: login, reset requests, the public rate limit
    scheduling/   one-instance-at-a-time coordination for the sweepers
    mail/         the EmailSender interface and its logging stand-in
    storage/      the ObjectStorage interface and its S3 implementation
    config/       security, JWT, MVC, OpenAPI
      env/          .env loading, and the fail-fast check that names a missing variable
  src/main/java/com/autox/demo/      the demo seed — loaded only under the "demo" profile
  src/main/resources/db/migration/   V1 … V13, Flyway owns the schema
  src/test/java/com/autox/           integration tests, Testcontainers
  src/test/resources/application-test.yml   the test profile: deviations only
  Dockerfile                         the API image Render runs: Java 21 JRE, non-root, sized for 512 MB

frontend/
  openapi.json                  the committed API contract — the client is generated from it
  scripts/fetch-openapi.mjs     refreshes it from a running backend
  scripts/build.mjs             the production build: ng build, then the security policy
  scripts/security-headers.mjs  the policy and headers, as pure functions, and their tests
  public/                       favicon, wordmark, robots.txt, the link-preview image
  src/environments/             apiBaseUrl, per build configuration
  src/styles/                   design tokens, base styles, shared form and button classes
  src/app/core/
    api/generated/              GENERATED, gitignored — never edited, never committed
    api/index.ts                the only import path the app uses for API types
    auth/                       token store, session state, 401 interceptor, role guards
    errors/                     API code + field -> French, exhaustive against the spec
    ui/                         one-shot messages handed between screens
  src/app/layout/               site header, footer, primary navigation
  src/app/shared/
    forms/                      validation rules + French text, text and select fields
    format/                     dates and numbers, through Intl rather than a pipe
    listing/                    row, card, price, spec line, gallery; French labels for enums
                                and for moderation reasons
    ui/                         the alert banner, filter chip, not-found block; the icon
                                component and its Lucide data (scripts/icons.mjs)
  src/app/features/
    home/                       the home page: hero search card, recent searches, shortcuts
    search/                     the results: filters, URL <-> query, the shared resolver
                                and count, recent searches (per browser)
    listing/                    one listing: gallery, specification, equipment, seller
    auth/                       sign in, register, forgot password, reset password
    account/                    sidebar layout, profile, company, change password, sessions
    seller/                     "Mes annonces", the four-step create/edit form, photo upload
    admin/                      moderation: three lists of one card
    errors/                     access refused, page not found

.github/workflows/backend.yml    CI: clean verify, then build and scan the API image
.github/workflows/frontend.yml   CI: install, lint, build, test, on frontend/**
.github/dependabot.yml           weekly upgrade proposals for every ecosystem
render.yaml                      the API service on Render (Blueprint); see docs/deployment.md
netlify.toml                     the site's build on Netlify; see docs/deployment.md

docs/            why things are the way they are — read these before changing them
```

### The docs worth reading first

| | |
|---|---|
| `docs/deferred.md` | **What is deliberately not built, and what each gap costs.** Start here. |
| `docs/weaknesses.md` | **Where the product is weakest, ranked — and, separately, what would be a security or privacy risk if it went public.** Read before deploying. |
| `docs/er-overview.md` | The data model in plain text |
| `docs/schema-decisions.md` | Why the schema is shaped as it is |
| `docs/auth-decisions.md` | Tokens, rotation, throttling |
| `docs/listing-decisions.md` | The state machine, quotas, expiry |
| `docs/image-decisions.md` | The upload handshake, variants, EXIF |
| `docs/third-party-assets.md` | The home page photo and the icons: source, licence, notices |
| `docs/search-decisions.md` | Keyset pagination and the measured query plans |
| `docs/price-indicator.md` | "Bonne affaire" and the rest: the comparables rule, why, and how it is computed |
| `docs/reference-decisions.md` | The reference endpoints and the public rate limit |
| `docs/account-and-correctness-decisions.md` | Price history, the password and session endpoints, and three fixed concurrency/keying bugs |
| `docs/test-config-decisions.md` | Why the test profile layers rather than replaces, what the suite was silently not testing, and the CI workflows |
| `docs/deployment.md` | **Deploying the demo**, step by step: Neon, Supabase Storage, Render, Netlify — every variable, seeding, the first admin, the checks afterwards. |
| `docs/frontend-decisions.md` | The generated API client, the single in-flight refresh, token storage, guards, error translation, design tokens |

---

## Conventions this codebase holds to

- **Flyway owns the schema.** Never edit an applied migration; add one. Hibernate validates
  and does not create.
- **Secrets come from the environment.** No credential is committed, and none has a
  fallback — a default password is a password in version control. Non-secret coordinates do
  carry local defaults, so a stock docker-compose stack starts with nothing exported.
- **A missing setting fails startup naming the variable.** Not with a Hikari assertion a
  hundred frames deep. `RequiredConfigurationEnvironmentPostProcessor` plus a
  `FailureAnalyzer` turn it into Boot's failure report; the check is on the Spring property
  rather than the variable, so it is correct under test too.
- **Query plans are measured, not assumed.** `SearchQueryPlanTest` asserts the plans at
  20,000 rows. Measuring found four real index defects that reading the DDL had not.
- **Tests use real infrastructure.** If something can only be tested against a mock, that is
  a reason to doubt the design.
- **A state change recorded before a thrown exception needs its own transaction.** This has
  bitten three times — refresh-token replay, login throttling, image failures — and each fix
  is a named component using `REQUIRES_NEW`. Spring's self-invocation is not proxied, so the
  separate bean is not optional. The newer throttles avoid the trap by *shape* instead:
  the service returns a decision and the caller throws, so there is no exception inside the
  transaction that did the writing.
- **The frontend never hand-writes an API type.** Models, enums and HTTP clients are
  generated from `frontend/openapi.json` on every build. If the application needs a type
  the API does not describe, that is a gap in the OpenAPI document to fix on the backend —
  which is how three real contract bugs were found. See `docs/frontend-decisions.md`.
- **The API's `detail` and `message` are never shown to a user.** They are untranslated
  developer text whose wording is not a contract. French comes from a table keyed on the
  stable `code`, typed `Record<ErrorCode, string>` so a new backend code breaks the
  frontend build until someone writes the French.
- **The test profile layers over the production configuration; it does not replace it.**
  `application-test.yml` holds only deliberate deviations, each with its reason, and nothing
  that merely restates a production value — a duplicate that silently stops matching is what
  that file used to be full of. It was once named `application.yml`, which shadowed the
  production file entirely and meant a test asserting configuration-driven behaviour could be
  asserting a framework default. `ConfigurationLayeringIntegrationTest` now guards both
  directions. See `docs/test-config-decisions.md`.
