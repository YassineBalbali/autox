# Deploying AutoX

The public demo runs on four free services:

| Part | Service | What it is |
|---|---|---|
| API | **Render**, free web service | The Docker image in `backend/Dockerfile`, 512 MB, a tenth of a CPU |
| Database | **Neon**, free plan | PostgreSQL 16 that sleeps after five idle minutes |
| Photos | **Cloudflare R2** | A private bucket; the browser uploads to it with URLs the API signs |
| Site | **Cloudflare Pages** | The Angular build, with its security headers in `_headers` |

The steps below are in the order to do them. Each service needs a value from one created
before it, and the one circular dependency is closed in step 6, which goes back to fix it.

## Before you start: the values you will collect

Keep these in a password manager as you go. The ones marked **secret** must never be
committed, pasted in an issue, or put anywhere but the service that needs them.

| Name used below | Where it comes from | Example |
|---|---|---|
| `SITE` | The Pages project name you choose (step 5) | `https://autox-demo.pages.dev` |
| `API` | The Render service name (step 3) | `https://autox-api.onrender.com` |
| `NEON_HOST` | Neon (step 1) | `ep-quiet-sun-a1b2c3d4.eu-central-1.aws.neon.tech` |
| `NEON_USER` | Neon (step 1) | `neondb_owner` |
| `NEON_PASSWORD` | Neon (step 1) — **secret** | |
| `R2_ENDPOINT` | Cloudflare (step 2) | `https://<account id>.r2.cloudflarestorage.com` |
| `R2_ACCESS_KEY_ID` | Cloudflare (step 2) — **secret** | |
| `R2_SECRET_ACCESS_KEY` | Cloudflare (step 2) — **secret** | |

**Choose the two names now**: a Pages project name (it becomes `<name>.pages.dev`) and a
Render service name (it becomes `<name>.onrender.com`). Both are first-come, first-served:
if one is taken, the service appends a few characters, and the real URL is the one it
shows you. Step 6 is where any difference gets corrected.

**Commit and push first.** Render and Pages build from GitHub, so they deploy what is on
`main`, not what is on your machine.

---

## Step 1 — Neon: the database

1. Sign up at neon.com and **create a project**:
   - **Postgres version: 16.** Neon offers newer versions; the app is built and tested on 16.
   - **Region: AWS Europe Central 1 (Frankfurt)**, next to the Render service.
2. **Project settings → Compute**: set autoscaling to **0.25 – 0.25 CU**. The free plan
   gives 100 CU-hours a month; a compute allowed to scale up to 2 CU spends them eight times
   faster for no benefit at this traffic. Leave scale-to-zero on (five minutes).
3. **Dashboard → Connect**: pick the `neondb` database and the `neondb_owner` role, and
   **switch "Connection pooling" off**. The connection string looks like:

   ```
   postgresql://neondb_owner:PASSWORD@ep-quiet-sun-a1b2c3d4.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
   ```

   From it, note `NEON_HOST` (between `@` and `/`), `NEON_USER` and `NEON_PASSWORD`.

**Why not the pooled connection:** the pooled endpoint is PgBouncer in transaction mode.
Flyway takes a session-level lock while it migrates, which transaction pooling does not
keep. The API has its own pool, so the direct endpoint is the right one.

**The JDBC URL** — Java does not take Neon's string as it is. The user and password move
into their own variables, and only `sslmode` is kept:

```
jdbc:postgresql://NEON_HOST/neondb?sslmode=require
```

There is nothing to create in the database. The API builds the schema on its first start.

## Step 2 — Cloudflare R2: photo storage

1. Cloudflare dashboard → **R2 Object Storage**. Enabling R2 asks for a payment method even
   on the free allowance (10 GB stored, a million writes and ten million reads a month — far
   above what the demo uses).
2. **Create bucket**:
   - name **`autox-photos`** (the name `render.yaml` expects);
   - location: **Automatic**, with the hint **Western Europe**;
   - **default storage class**, and **not** an EU jurisdiction bucket — that one has a
     different endpoint (`…eu.r2.cloudflarestorage.com`) from the one used below.
   - Leave **public access off**. The API hands out signed URLs that expire.
3. **R2 → Manage API tokens → Create API token**:
   - permission **Object Read & Write**;
   - **Specify bucket: `autox-photos` only**;
   - no expiry.

   The page shows the **Access Key ID** and **Secret Access Key** once — note them as
   `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` — and the S3 endpoint,
   `https://<account id>.r2.cloudflarestorage.com`: that is `R2_ENDPOINT`.
4. **The CORS rule** — bucket → Settings → CORS policy → Add. The browser uploads straight to
   the bucket, so without this rule every photo upload fails in the browser, and the server
   never finds out:

   ```json
   [
     {
       "AllowedOrigins": ["https://autox-demo.pages.dev"],
       "AllowedMethods": ["GET", "PUT"],
       "AllowedHeaders": ["Content-Type"],
       "MaxAgeSeconds": 3600
     }
   ]
   ```

   Use your planned `SITE` value; step 6 corrects it if Pages gives you a different one.
   `Content-Type` is needed because the upload URL is signed over it and the browser sends it.

## Step 3 — Render: the API

`render.yaml` at the repository root describes the service. Render calls this a
**Blueprint**.

1. render.com → **New → Blueprint** → connect GitHub → choose `YassineBalbali/autox`.
2. Render reads `render.yaml` and lists one service, `autox-api`. Rename it here if you chose
   another name.
3. It asks for the values the file leaves blank (`sync: false`). Enter:

   | Variable | Value |
   |---|---|
   | `AUTOX_DB_URL` | `jdbc:postgresql://NEON_HOST/neondb?sslmode=require` |
   | `AUTOX_DB_USERNAME` | `NEON_USER` |
   | `AUTOX_DB_PASSWORD` | `NEON_PASSWORD` |
   | `AUTOX_S3_ENDPOINT` | `R2_ENDPOINT` |
   | `AUTOX_S3_ACCESS_KEY` | `R2_ACCESS_KEY_ID` |
   | `AUTOX_S3_SECRET_KEY` | `R2_SECRET_ACCESS_KEY` |
   | `AUTOX_CORS_ALLOWED_ORIGINS` | `SITE` — e.g. `https://autox-demo.pages.dev`, no trailing slash |
   | `AUTOX_PASSWORD_RESET_URL` | `SITE` + `/reinitialiser-mot-de-passe` |
   | `AUTOX_JWT_ISSUER` | `API` — e.g. `https://autox-api.onrender.com` |

   Render asks for these **only this once**. Later changes go through the service's
   **Environment** page, never through `render.yaml`.
4. **Apply.** The first build takes several minutes: Maven downloads every dependency. Then
   the API starts, which at a tenth of a CPU takes **two and a half to three and a half
   minutes**. Render shows "Deploy live" once `/actuator/health/liveness` answers.
5. Open `API/actuator/health/readiness`. It should say `{"status":"UP"}`, which means the API
   reaches Neon. The log should also say `Object storage is ready`, which means it reaches R2.
   Flyway created the schema during that first start.

### Everything the service is configured with

`render.yaml` sets the rest. For reference — and if you would rather enter the settings by
hand (New → Web Service → the repository), these are all of them:

| Setting | Value |
|---|---|
| Language / runtime | Docker |
| Branch | `main` |
| Region | Frankfurt |
| Dockerfile path | `./backend/Dockerfile` |
| Docker build context | `./backend` |
| Instance type | Free |
| Health check path | `/actuator/health/liveness` |
| Auto-deploy | After CI checks pass |
| Build filter | `backend/**` |

| Variable | Value | Why |
|---|---|---|
| the nine in the table above | | |
| `AUTOX_JWT_SECRET` | generated by Render (Blueprint) — by hand, `openssl rand -base64 48` | Signs access tokens. Never the local one. |
| `AUTOX_S3_REGION` | `auto` | R2's region name |
| `AUTOX_S3_BUCKET` | `autox-photos` | |
| `AUTOX_S3_PATH_STYLE` | `true` | One storage host, so one origin in the site's policy |
| `AUTOX_S3_CREATE_BUCKET` | `false` | The bucket exists, with its CORS rule |
| `AUTOX_CLIENT_IP_HEADER` | `True-Client-IP` | Where Render puts the real client address (see step 8) |
| `AUTOX_LOG_LEVEL` | `INFO` | |
| `AUTOX_DB_POOL_SIZE` | `5` | |
| `AUTOX_DB_POOL_MIN_IDLE` | `0` | Lets Neon sleep: see "Keeping Neon inside its allowance" |
| `AUTOX_DB_IDLE_TIMEOUT_MS` | `60000` | 〃 |
| `AUTOX_SCHEDULE_LISTING_EXPIRY` | `PT6H` | 〃 |
| `AUTOX_SCHEDULE_IMAGE_CLEANUP` | `PT6H` | 〃 |
| `AUTOX_SCHEDULE_AUTH_MAINTENANCE` | `0 17 */6 * * *` | 〃 |
| `SERVER_TOMCAT_THREADS_MAX` | `20` | 200 threads' stacks do not fit in 512 MB |
| `AUTOX_LISTING_EXPIRYDAYS` | `3650` | The default 60 days would expire every seeded listing two months in |
| `AUTOX_LISTING_PROFESSIONNELACTIVELIMIT` | `3` | Dealers are uncapped by default, and anyone can register as one |

`PORT` is set by Render, and the image listens on it. Do not set `AUTOX_PORT`.

There is no `.env` in the image. If a required variable is missing, the API stops at
startup and the log names the variable.

## Step 4 — The demo data

The demo seed runs at startup when the `demo` profile is on. Render's free plan has no
shell and no one-off jobs, so the profile goes on for one deploy and comes off again:

1. Render → `autox-api` → **Environment** → add `SPRING_PROFILES_ACTIVE` = `demo` →
   **Save, rebuild and deploy**.
2. **Logs**: wait for the line

   ```
   Seeded 60 demo listings, 8 demo accounts and 10 demo makes. This is invented data …
   ```

   In a local test with Render's limits, it took **about twelve minutes** after startup:
   60 listings and their photos, each drawn, uploaded and resized into three sizes. The
   service is live and answering throughout. The site shows the listings as they are
   written; only `/actuator/health/readiness` says 503 until the seed is done.
3. **Remove `SPRING_PROFILES_ACTIVE`** → Save, rebuild and deploy. Left on, it would only
   find the data present and do nothing — but a seeder has no business staying wired to a
   live database.

The seeded sellers' passwords are random and never shown, so nobody can sign in as them.

**If the seed fails** — the log shows `Application run failed` with the reason, usually
storage (wrong key, endpoint or bucket name). Storage is checked before anything is written,
so a misconfiguration fails cleanly. If it fails partway regardless, the next start reports
"Demo data is already present" and skips it. Then do one deploy with
`AUTOX_DEMO_ACTION=remove` added, and one without it.

**To reset the demo later** (once visitors have filled it with test listings): deploy once
with `SPRING_PROFILES_ACTIVE=demo` **and** `AUTOX_DEMO_ACTION=remove`, then once with only
the profile (seed again), then remove both. Removal deletes exactly what the seed recorded.
Listings visitors created are left alone; remove those from the moderation screen.

## Step 5 — Cloudflare Pages: the site

1. Cloudflare dashboard → **Workers & Pages → Create → Pages → Connect to Git** → choose
   `YassineBalbali/autox`.
2. Set up the build:

   | Setting | Value |
   |---|---|
   | Project name | your chosen name, e.g. `autox-demo` |
   | Production branch | `main` |
   | Framework preset | None |
   | Build command | `npm run build` |
   | Build output directory | `dist/frontend/browser` |
   | Root directory | `frontend` |

3. **Environment variables** (Production). These are compiled into the site: a static site has no
   server to read them at run time. Changing one means rebuilding.

   | Variable | Value |
   |---|---|
   | `NODE_VERSION` | `24.20.0` — CI's version. Angular 22 refuses older ones. |
   | `AUTOX_API_BASE_URL` | `API` — e.g. `https://autox-api.onrender.com` |
   | `AUTOX_STORAGE_ORIGINS` | `R2_ENDPOINT` — e.g. `https://<account id>.r2.cloudflarestorage.com` |
   | `AUTOX_SITE_URL` | `SITE` — e.g. `https://autox-demo.pages.dev` |

4. **Save and deploy.** At the end, the build log prints the API, storage and site URLs it used,
   and the security policy. Check them there.
5. Settings → Builds → **Build watch paths**: include `frontend/*`, so a backend-only commit
   does not rebuild the site.

No `_redirects` or `404.html` is needed. Without a `404.html`, Pages serves the app for
every path itself, with a 200 status. A `404.html` would switch that off, and every shared
link to a listing would then come back with a 404 status, which link previews take at its word.

## Step 6 — Close the loop

Open the site. If Pages or Render gave you a different URL from the one you planned:

- **Render → Environment**: correct `AUTOX_CORS_ALLOWED_ORIGINS`, `AUTOX_PASSWORD_RESET_URL`
  and `AUTOX_JWT_ISSUER`, then save (it redeploys).
- **R2 → bucket → CORS policy**: correct `AllowedOrigins`.
- **Pages → Environment variables**: correct `AUTOX_API_BASE_URL` / `AUTOX_SITE_URL`, then
  **retry the deployment** — that is the only way a build-time value changes.

A custom domain later means the same three edits, with the new origin.

## Step 7 — The uptime monitor

Render puts a free service to sleep after 15 minutes without a request; waking it is a
full cold start (about three minutes). A monitor keeps it awake:

- **URL: `API/actuator/health/liveness`** — not `/actuator/health`. The full health check
  queries the database, so a ping every ten minutes would wake Neon every ten minutes, all
  month (see below). Liveness answers from memory.
- Interval: 10 minutes. Method GET; it answers 200 and `{"status":"UP"}`.

A month of one service always on is about 730 of the 750 free instance-hours Render gives a
workspace. Anything else free in the same workspace will run out of hours.

## Step 8 — Check that the rate limits cannot be dodged

Every throttle — registration, sign-in, password reset, the public read limit — keys on
the client's address. Render passes requests through Cloudflare and then its own proxy.
Its `X-Forwarded-For` keeps whatever the client wrote at the front, and its last
untrusted entry is a Cloudflare edge shared by thousands of visitors. Neither is
usable. Render overwrites `True-Client-IP` with the real address, and the API reads that
(`AUTOX_CLIENT_IP_HEADER`), only when the connection comes from a private-network proxy.
`ClientAddressIntegrationTest` covers this locally; this check proves it on Render.

From a terminal (Git Bash works), with your `API`:

```bash
API=https://autox-api.onrender.com
curl -s https://api64.ipify.org; echo     # your own address, as the internet sees it

for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "attempt $i: %{http_code}\n" -X POST "$API/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 198.51.100.$i" \
    -H "True-Client-IP: 198.51.100.$i" \
    -H "X-Real-IP: 198.51.100.$i" \
    -d '{"email":"xff-check@autox.test","password":"correct-horse-battery","role":"PARTICULIER","displayName":"XFF check","countryCode":"FR","locale":"fr-FR"}'
done
```

**Expected:** `201`, then `409` four times (the address is taken), then **`429`**. Five
attempts an hour per address, and every attempt counts, whatever it forged.

Then in **Neon → SQL Editor**:

```sql
SELECT scope, scope_key, event_count FROM throttle_counter WHERE scope = 'REGISTRATION_IP';
```

**Expected:** one row whose `scope_key` is **your address** from the first command. An
IPv6 address appears as its /64 (`2a01:e0a:1:2:0:0:0:0/64`). There is no row for `198.51.100.x`.

If instead:

- **six answers and no `429`**, or rows for `198.51.100.x`: the forged headers are being
  believed. The limits do not hold. Stop and look at the configuration before going further.
- **one row keyed on a `10.x` or `172.x` address**: the API is not seeing `True-Client-IP`,
  and every visitor shares one bucket. Nothing can be dodged, but the limits would trip for
  everyone together. Check `AUTOX_CLIENT_IP_HEADER` is set exactly as above. If it is,
  Render's proxy is reaching the API from an address outside the private ranges Tomcat
  trusts by default; the log of a request shows it. Name it in
  `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` (a regular expression).

Then clean up, so your own registration is not blocked for an hour:

```sql
DELETE FROM throttle_counter WHERE scope = 'REGISTRATION_IP';
DELETE FROM app_user WHERE email = 'xff-check@autox.test';
```

## Step 9 — An administrator account

There is no endpoint that makes an administrator, on purpose:

1. On the site, **create an account** with your own email (any role).
2. **Neon → SQL Editor**:

   ```sql
   UPDATE app_user SET role = 'ADMIN' WHERE email = 'you@example.com';
   ```

   It should report `UPDATE 1`. Emails are stored in lower case. If it says `UPDATE 0`,
   type the address in lower case.
3. **Sign out and in again**: the role travels in the access token. "Administration" then
   appears in the account menu, with the seeded listings waiting in the moderation queue.

## Step 10 — Check from outside

- `https://securityheaders.com/?q=SITE` — expect an A, with a Content-Security-Policy.
- The browser console on the home page, a listing, and the photo step of "Vendre": no
  red. The photo step is the only place that touches R2 from the browser, so it is the
  test of the CORS rule and of `AUTOX_STORAGE_ORIGINS`.
- Paste a listing link into a messaging app: the preview should show the AutoX card.

---

## Keeping Neon inside its allowance

The free plan's 100 CU-hours a month cover a 0.25 CU compute for about 400 hours. A month
has 730. **Neon must therefore sleep for most of the month**, and three things would keep it awake:

| What | How it is prevented |
|---|---|
| The connection pool pinging its open connections every two minutes | `AUTOX_DB_POOL_MIN_IDLE=0`, `AUTOX_DB_IDLE_TIMEOUT_MS=60000`: the pool empties after a minute idle |
| The background sweeps, every 10 and 15 minutes | Every 6 hours (`AUTOX_SCHEDULE_*`) |
| Health checks that query the database | Render and the uptime monitor both use `/actuator/health/liveness`, which does not |

With all three in place, Neon wakes for visitors and a handful of sweeps a day. Check
**Neon → Billing/Usage** after the first week. If compute hours climb by a few each day
with no visitors, something is holding a connection open.

The first request after Neon has slept waits under a second while it resumes. That is
the price, and on a demo it is the right one.

## Render-specific: what would have broken, and what changed

These are the parts of the previous configuration that would have failed, or quietly
misbehaved, on these services. Each is fixed in the repository. The list is here so the
reason for each setting is not lost.

| Problem | Fix |
|---|---|
| The API listened on `AUTOX_PORT` (8080). Render routes to `PORT` (10000) | The image passes `PORT` to the server |
| The client address came from the **first** value of `X-Forwarded-For` — client-written on Render, so every rate limit could be dodged | Tomcat's own proxy handling (`native`), trusting private-network proxies only, reading Render's `True-Client-IP` |
| Hikari's idle connections, the sweeps and a database health check would have kept Neon awake and spent its month's compute in about two weeks | The settings above |
| R2 rejects the AWS SDK's default chunked upload signing with a 403. Every photo would have failed processing — invisible locally, where MinIO accepts it | Chunked encoding off in `StorageConfig` |
| The site build wrote a `404.html`, which turns off Pages' single-page fallback: every deep link would have been served with a 404 status | No `404.html` or `_redirects` is written |
| The JVM's defaults in 512 MB: a heap too small for image work, and nothing bounding the rest | Memory flags in the Dockerfile, measured below |
| 200 Tomcat threads | `SERVER_TOMCAT_THREADS_MAX=20` |
| The demo seed, run against the demo's dealer cap of 3, stopped at the first dealer's fourth listing and left a half-seed that later starts skipped | The seed's own accounts are uncapped |
| The seed wrote rows before checking storage, so a storage fault failed it halfway | Storage is checked first |
| A missing variable's startup error told the operator to create a `.env` | It says to set the variable in the service's environment, then gives the local advice |

**Measured locally** with Render's limits (`--memory=512m --cpus=0.1`, no `.env`, the
variables above): startup in 145–195 seconds; the full seed in about twelve minutes, at
roughly twelve seconds a listing; memory peaking at about 340 MB of the 512 during the seed;
the pool down to zero database connections a minute after the last request; a liveness ping
opening none; and the forged-header check of step 8 answering 201, 409 ×4, 429.

## What the demo will and will not do

- **No email is sent.** "Mot de passe oublié" accepts the request and nothing arrives. The
  link is written to the API's log, which only you can read on Render. Accounts are not
  for real use, and the footer says so on every page.
- **Anything a visitor publishes is public at once**, photos included, and is reviewed
  afterwards. The caps bound how much; the moderation screen removes it. There is no
  setting that turns publishing off.
- **Cold starts.** If the monitor stops, the first visitor waits for a full start
  (about three minutes). If Neon is asleep, the first query waits about a second.
- **Pages preview deployments** (every non-`main` branch) get their own URLs. They are not in
  `AUTOX_CORS_ALLOWED_ORIGINS`, so they load but cannot reach the API. That is deliberate.
- **Swagger UI** is public at `API/swagger-ui.html`, which for a portfolio is arguably the
  point.

See `docs/weaknesses.md` for the rest, most of which matters only with real users.
