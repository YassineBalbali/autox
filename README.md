# AutoX

A vehicle marketplace — buying and selling cars — built for the French market first and
multi-country from the data model up. Competitors are La Centrale, Leboncoin and Mobile.de.

**This repository currently contains the backend only.** The MVP API is complete: accounts,
listings, photos, public search and the reference vocabularies. The Angular frontend is the
next piece of work and has not started.

| | |
|---|---|
| **Backend** | Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, S3-compatible object storage |
| **Frontend** | Angular (standalone components, mobile-first) — not started |
| **Layout** | `backend/` and `frontend/` at the repository root |

---

## The two things to know before reading the code

**It is headless and API-first.** Every rule lives in the backend. The web app will be a pure
API consumer, and a native mobile app will later call the *same* endpoints — so no endpoint
assumes a browser, a cookie or a session. The contract is the OpenAPI document the application
serves, not anything the frontend happens to do.

**It is multi-country from day one.** Country, currency and locale are in the schema now, not
bolted on later. Prices are integers in the currency's minor unit with an ISO 4217 code beside
them; distances are always kilometres on the wire whatever a market displays. Only France is
enabled today.

---

## Running it from a clean checkout

### You need

- **JDK 21** — `java -version` should say 21
- **Maven 3.9+** — or use the wrapper if one is added later
- **Docker** with Compose — for PostgreSQL, object storage, and the integration tests

### Four steps

```bash
git clone <this repository>
cd autox

cp .env.example .env     # then edit it: see below
docker compose up -d     # PostgreSQL on 5433, MinIO on 9000/9001

cd backend
mvn spring-boot:run      # http://localhost:8080
```

Flyway applies the migrations on first boot — there is no schema step to run by hand, and
nothing to create but the database the container already creates. Hibernate runs with
`ddl-auto: validate`: it checks the entities against the migrated schema and refuses to start
if they disagree, so a drifting entity fails fast instead of silently.

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

There is deliberately **no default for either secret**. A missing one fails startup rather
than falling back to something weak, and `.env` is gitignored — nothing in version control
holds a credential.

### Check it came up

```bash
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
mvn test                 # 167 tests, needs Docker running
```

Every test is an integration test against a real PostgreSQL and a real S3-compatible store
in Testcontainers. Nothing about authentication, storage or SQL is mocked: a passing test
exercises the code path a phone would. The containers start once and are shared, so the suite
pays that cost a single time (expect roughly five minutes in total).

---

## The API

Served by the running application:

| | |
|---|---|
| **OpenAPI JSON** | `/v3/api-docs` |
| **OpenAPI YAML** | `/v3/api-docs.yaml` |
| **Swagger UI** | `/swagger-ui.html` |

All three are public, because a closed specification on a public API protects nothing. The
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

---

## Repository map

```
backend/
  src/main/java/com/autox/
    api/          error contract: ApiException, ErrorCode, RFC 9457 handler
    auth/         register, login, refresh, logout; login throttling
    user/         accounts, profiles, refresh tokens
    listing/      the seller's listings; state machine; photos (listing/image)
    search/       public search and detail; keyset cursors
    reference/    countries and currencies; the reference endpoints
    catalog/      vehicle make/model/trim, equipment vocabulary
    throttle/     the shared counter: login throttling and the public rate limit
    storage/      the ObjectStorage interface and its S3 implementation
    config/       security, JWT, MVC, OpenAPI
  src/main/resources/db/migration/   V1 … V10, Flyway owns the schema
  src/test/java/com/autox/           integration tests, Testcontainers

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

---

## Conventions this codebase holds to

- **Flyway owns the schema.** Never edit an applied migration; add one. Hibernate validates
  and does not create.
- **Secrets come from the environment.** No credential is committed, and none has a fallback.
- **Query plans are measured, not assumed.** `SearchQueryPlanTest` asserts the plans at
  20,000 rows. Measuring found four real index defects that reading the DDL had not.
- **Tests use real infrastructure.** If something can only be tested against a mock, that is
  a reason to doubt the design.
- **A state change recorded before a thrown exception needs its own transaction.** This has
  bitten three times — refresh-token replay, login throttling, image failures — and each fix
  is a named component using `REQUIRES_NEW`. Spring's self-invocation is not proxied, so the
  separate bean is not optional.
