# AutoX

A vehicle marketplace — buying and selling cars — built for the French market first and
multi-country from the data model up. Competitors are La Centrale, Leboncoin and Mobile.de.

**The backend MVP is complete** — accounts with the full password and session lifecycle,
listings, photos, public search and the reference vocabularies. **The frontend has its
scaffold and API layer** and no screens yet: the generated client, the auth plumbing, error
translation and the design tokens are in place, and the first screen is the next piece of
work.

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
mvn spring-boot:run      # 3. the API, on AUTOX_PORT (8080 unless .env says otherwise)
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
`src/environments/environment.development.ts` — port **8081** by default, matching
`AUTOX_PORT` in `.env.example`; keep the two in step.

One more pair has to stay in step: `autox.mail.password-reset-url` is the link in the reset
email, and its path must match the Angular route `/reinitialiser-mot-de-passe`. A mismatch
produces a link that loads the application and renders nothing — no error, on either side.
With `AUTOX_MAIL_PROVIDER=log` the link is written to the backend log, which is what makes
the reset flow walkable locally.

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
curl http://localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
#
# For a second or two after the log says "Started AutoxApplication" this answers
# OUT_OF_SERVICE: readiness flips to accepting traffic on ApplicationReadyEvent, which
# fires just after. That is the probe working, not a fault.

curl http://localhost:8080/api/v1/reference/countries
# [{"code":"FR","name":"France","defaultLocale":"fr-FR", ... "currencyMinorUnit":2}]
```

Then open **http://localhost:8080/swagger-ui.html** for the whole API.

### Ports already in use

Both are common collisions, and both are a variable away from fixed:

- **5432** — a natively installed PostgreSQL wins over Docker's port proxy, and the
  connection silently reaches the *wrong server*. That is why compose maps **5433**. If 5433
  is also taken, change `POSTGRES_PORT` and the port inside `AUTOX_DB_URL` together.
- **8080** — set `AUTOX_PORT`.

### Tests

```bash
cd backend
mvn clean verify         # 231 tests, needs Docker running
```

Every test is an integration test against a real PostgreSQL and a real S3-compatible store
in Testcontainers. Nothing about authentication, storage or SQL is mocked: a passing test
exercises the code path a phone would. The containers start once and are shared, so the suite
pays that cost a single time (expect roughly five minutes in total).

**Use `clean`.** The tests run against the production `application.yml` with
`application-test.yml` layered on top, and Maven does not remove resources that have been
renamed — so an incremental build can keep loading a stale copy and run the whole suite
against the wrong configuration. `ConfigurationLayeringIntegrationTest` fails loudly if that
happens, with the remedy in the message.

### CI

Two workflows, both on **every push** to every branch and on pull requests into `main`,
each filtered to the paths that can change its result.

| Workflow | Watches | Runs |
|---|---|---|
| `.github/workflows/backend.yml` | `backend/**`, `frontend/openapi.json`, itself | `mvn -B clean verify` |
| `.github/workflows/frontend.yml` | `frontend/**`, itself | `npm ci`, generate, lint, build, test |

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
  There are no page numbers and no total count.
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

One interface, five methods, no vendor type: MinIO, Cloudflare R2, Scaleway, Backblaze B2 and
AWS S3 all speak this protocol, so the provider is configuration rather than code.

### Transactional email

| Variable | Default | Notes |
|---|---|---|
| `AUTOX_MAIL_PROVIDER` | `log` | **`log` writes the message to the application log instead of sending it.** That makes the password reset flow walkable locally — the link is in the console — and is a credential leak in production, since anyone who can read the log can take over any account. It warns at every startup. A real provider is an `EmailSender` bean conditional on its own value here. |
| `AUTOX_MAIL_FROM` | `no-reply@autox.local` | Unused by the logging sender; required by every real one. |
| `AUTOX_MAIL_FROM_NAME` | `AutoX` | |
| `AUTOX_PASSWORD_RESET_URL` | `http://localhost:4200/reset-password` | The frontend page that takes the token; the email links to it with `?token=…`. The API cannot guess it — the web app is deployed separately. |

### Optional

| Variable | Default | Notes |
|---|---|---|
| `AUTOX_PORT` | `8080` | |
| `AUTOX_LOG_LEVEL` | `INFO` | Application logging only |
| `AUTOX_DB_POOL_SIZE` | `10` | |
| `AUTOX_JWT_ISSUER` | `https://api.autox.local` | |
| `AUTOX_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Comma-separated. Browsers only — a native app is not subject to CORS. |
| `AUTOX_LOGIN_THROTTLE_ENABLED` | `true` | Leave on. |
| `AUTOX_PUBLIC_RATE_LIMIT_ENABLED` | `true` | Per-IP limit on the public reads |
| `AUTOX_PUBLIC_RATE_LIMIT_REQUESTS` | `300` | Per minute, per address |

The compose file also reads `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
`POSTGRES_PORT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_PORT` and
`MINIO_CONSOLE_PORT`. The `POSTGRES_*` and `MINIO_*` values must match their `AUTOX_*`
counterparts, since one pair configures the container and the other the client.

### Deploying behind a proxy

`server.forward-headers-strategy=framework` is set, so Spring honours `X-Forwarded-For` and
the application sees the real client address. **That is only safe if the proxy is trusted to
overwrite the header**: a caller that can set it freely can forge the key the rate limiter
and the login throttle count against, and sidestep both. Terminate at a proxy you control.

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
    listing/      the seller's listings; state machine; price history; photos (listing/image)
    search/       public search and detail; keyset cursors
    reference/    countries and currencies; the reference endpoints
    catalog/      vehicle make/model/trim, equipment vocabulary
    throttle/     the shared counter: login, reset requests, the public rate limit
    scheduling/   one-instance-at-a-time coordination for the sweepers
    mail/         the EmailSender interface and its logging stand-in
    storage/      the ObjectStorage interface and its S3 implementation
    config/       security, JWT, MVC, OpenAPI
      env/          .env loading, and the fail-fast check that names a missing variable
  src/main/resources/db/migration/   V1 … V12, Flyway owns the schema
  src/test/java/com/autox/           integration tests, Testcontainers
  src/test/resources/application-test.yml   the test profile: deviations only

frontend/
  openapi.json                  the committed API contract — the client is generated from it
  scripts/fetch-openapi.mjs     refreshes it from a running backend
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
    forms/                      validation rules + French text, field component, submit state
    format/                     dates, through Intl rather than a pipe
    ui/                         the alert banner
  src/app/features/
    auth/                       sign in, register, forgot password, reset password
    account/                    sidebar layout, profile, company, change password, sessions
    errors/                     access refused

.github/workflows/backend.yml    CI: clean verify, on backend/** and openapi.json
.github/workflows/frontend.yml   CI: install, lint, build, test, on frontend/**

docs/            why things are the way they are — read these before changing them
```

### The docs worth reading first

| | |
|---|---|
| `docs/deferred.md` | **What is deliberately not built, and what each gap costs.** Start here. |
| `docs/weaknesses.md` | An honest assessment of where the backend is weakest. |
| `docs/er-overview.md` | The data model in plain text |
| `docs/schema-decisions.md` | Why the schema is shaped as it is |
| `docs/auth-decisions.md` | Tokens, rotation, throttling |
| `docs/listing-decisions.md` | The state machine, quotas, expiry |
| `docs/image-decisions.md` | The upload handshake, variants, EXIF |
| `docs/search-decisions.md` | Keyset pagination and the measured query plans |
| `docs/reference-decisions.md` | The reference endpoints and the public rate limit |
| `docs/account-and-correctness-decisions.md` | Price history, the password and session endpoints, and three fixed concurrency/keying bugs |
| `docs/test-config-decisions.md` | Why the test profile layers rather than replaces, what the suite was silently not testing, and the CI workflows |
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
