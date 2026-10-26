# Deploying AutoX

The public demo runs on four free services:

| Part | Service | What it is |
|---|---|---|
| Database | **Neon**, free plan | PostgreSQL 16 that sleeps after five idle minutes |
| Photos | **Supabase Storage**, free plan | A private bucket, reached over Supabase's S3-compatible endpoint; the browser uploads to it with URLs the API signs |
| API | **Render**, free web service | The Docker image in `backend/Dockerfile`, 512 MB, a tenth of a CPU |
| Site | **Netlify**, free plan | The Angular build, with its security headers in `_headers` and its single-page fallback in `_redirects` |

Do the steps below in order. Each service needs values from those created before it. The
one circular dependency (the API and the site each need the other's URL) is closed in
step 6, which goes back to fix it.

## Before you start: the values you will collect

Keep these in a password manager as you go. The ones marked **secret** must never be
committed, pasted in an issue, or put anywhere but the service that needs them.

| Name used below | Where it comes from | Example |
|---|---|---|
| `NEON_HOST` | Neon (step 1) | `ep-quiet-sun-a1b2c3d4.eu-central-1.aws.neon.tech` |
| `NEON_USER` | Neon (step 1) | `neondb_owner` |
| `NEON_PASSWORD` | Neon (step 1) — **secret** | |
| `S3_ENDPOINT` | Supabase (step 2) | `https://abcdefghijklmnopqrst.supabase.co/storage/v1/s3` |
| `S3_REGION` | Supabase (step 2) | `eu-west-1` |
| `S3_ACCESS_KEY_ID` | Supabase (step 2) — **secret** | |
| `S3_SECRET_ACCESS_KEY` | Supabase (step 2) — **secret** | |
| `API` | The Render service name (step 3) | `https://autox-api-kyuo.onrender.com` |
| `SITE` | The Netlify project name (step 5) | `https://autox-demo.netlify.app` |

**Choose the two names now**: a Render service name and a Netlify project name (it becomes
`<name>.netlify.app`). **Expect Render to add a suffix.** The real deployment asked for
`autox-api` and got `https://autox-api-kyuo.onrender.com`, because Render appends random
characters when a name is taken, which short names usually are. Netlify kept
`autox-demo`. Either way, the real URL is the one the service shows you once it exists, and
step 6 corrects everything that used the planned one.

**Commit and push first.** Render and Netlify build from GitHub, so they deploy what is on
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

## Step 2 — Supabase Storage: photos

AutoX uses Supabase for storage only. Its database, auth and APIs go unused, and the S3
keys below can reach every bucket in the project, so **give AutoX a project of its own**.

1. supabase.com → **New project**:
   - name: `autox`, on the free plan;
   - a database password: Supabase requires one. AutoX never uses it, but keep it;
   - **region: West EU (Ireland)**, which is `eu-west-1`.
2. **Storage → New bucket**:
   - name **`autox-photos`** (the name `render.yaml` expects);
   - **Public bucket: off.** The API hands out signed URLs that expire.
3. **Storage → S3 settings.** Depending on the dashboard version this is under Storage →
   Settings or Project Settings → Storage.
   - Make sure **S3 protocol connection is enabled**.
   - Note the **Endpoint** as `S3_ENDPOINT` and the **Region** as `S3_REGION`. Use the
     endpoint exactly as the page shows it, `/storage/v1/s3` included.
   - **S3 access keys → New access key**, described as `autox-api`. Note the **Access key ID**
     as `S3_ACCESS_KEY_ID` and the **Secret access key** as `S3_SECRET_ACCESS_KEY`. The
     secret is shown once.
4. **Check that browsers may upload to it.** The browser sends each photo straight to this
   endpoint, from the site's domain, so the endpoint has to answer the browser's CORS
   preflight. Supabase has no CORS setting to configure: its gateway answers for itself.
   Check it now, before anything depends on it (Git Bash works):

   ```bash
   curl -s -i -X OPTIONS "S3_ENDPOINT/autox-photos/cors-check.jpg" \
     -H "Origin: https://autox-demo.netlify.app" \
     -H "Access-Control-Request-Method: PUT" \
     -H "Access-Control-Request-Headers: content-type" | grep -i -E '^HTTP|^access-control'
   ```

   **Expected:** a `200` or `204` status, `access-control-allow-origin` naming the origin
   or `*`, and `access-control-allow-methods` including `PUT`. If any is missing, stop:
   photo upload cannot work from a browser, and no AutoX setting changes that.

**The free project pauses after a week without database activity.** The storage server
records every bucket and object in the project's database, so photo traffic counts as
activity. The API also checks the bucket every twelve hours
(`AUTOX_SCHEDULE_STORAGE_CHECK`), which runs a query there even when nobody visits.
Supabase does not document exactly what it counts, so treat this as likely rather than
certain. Supabase emails a warning before pausing. **If it pauses**, every photo and every
upload fails until you press **Resume project** in the dashboard. The rest of the site
keeps working.

Free allowances: 1 GB stored (the demo seed uses about 36 MB) and 5 GB of egress a month.
Every photo a visitor views is egress.

## Step 3 — Render: the API

`render.yaml` at the repository root describes the service. Render calls this a
**Blueprint**.

1. render.com → **New → Blueprint** → connect GitHub → choose `YassineBalbali/autox`.
2. Render reads `render.yaml` and lists one service, `autox-api`. Rename it here if you chose
   another name.
3. It asks for the values the file leaves blank (`sync: false`). Enter:

   | Variable | Value | From |
   |---|---|---|
   | `AUTOX_DB_URL` | `jdbc:postgresql://NEON_HOST/neondb?sslmode=require` | Neon |
   | `AUTOX_DB_USERNAME` | `NEON_USER` | Neon |
   | `AUTOX_DB_PASSWORD` | `NEON_PASSWORD` | Neon |
   | `AUTOX_S3_ENDPOINT` | `S3_ENDPOINT` — `https://<ref>.supabase.co/storage/v1/s3` | Supabase |
   | `AUTOX_S3_ACCESS_KEY` | `S3_ACCESS_KEY_ID` | Supabase |
   | `AUTOX_S3_SECRET_KEY` | `S3_SECRET_ACCESS_KEY` | Supabase |
   | `AUTOX_CORS_ALLOWED_ORIGINS` | `SITE` — e.g. `https://autox-demo.netlify.app`, no trailing slash | Netlify (planned) |
   | `AUTOX_PASSWORD_RESET_URL` | `SITE` + `/reinitialiser-mot-de-passe` | Netlify (planned) |
   | `AUTOX_JWT_ISSUER` | `API` — e.g. `https://autox-api-kyuo.onrender.com` | Render (planned) |

   Render asks for these **only this once**. Later changes go through the service's
   **Environment** page, never through `render.yaml`.

   **Check every key, character by character.** A mistyped key (`AUTOX_DB_PASWORD`,
   `AUTOX_S3_ENDPONT`) was the easiest mistake to make in the real deployment, and the
   hardest to see. Nothing rejects an unknown variable: it is simply never read, and the
   setting it was meant for keeps its default. The keys `render.yaml` creates are spelled
   right. Keys typed by hand on the Environment page are not checked by anything. Copy
   them from this guide rather than typing them. What a typo looks like:
   - a **required** variable (the database URL or password, the JWT secret, the storage
     keys): the API stops at startup and the log names the variable it cannot find. Look
     for a near-miss of that name on the Environment page;
   - an **optional** one: the API starts normally and quietly uses the default. A
     mistyped `AUTOX_CORS_ALLOWED_ORIGINS` leaves only `http://localhost:4200` allowed,
     so every call from the site fails in the browser with a CORS error. A mistyped
     `AUTOX_CLIENT_IP_HEADER` makes step 8 show a `10.x` address.
4. **Check `AUTOX_S3_REGION`** before applying. `render.yaml` sets it to `eu-west-1`. If
   Supabase showed a different region, change it on the Environment page once the service
   exists.
5. **Apply.** The first build takes several minutes: Maven downloads every dependency. Then
   the API starts, which at a tenth of a CPU takes **two and a half to three and a half
   minutes**. Render shows "Deploy live" once `/actuator/health/liveness` answers.
6. Open `API/actuator/health/readiness`. It should say `{"status":"UP"}`, which means the
   API reaches Neon. The log should also say `Object storage is ready`, which means it
   reaches Supabase with those keys. If it says `Object storage is not usable` instead, the
   endpoint, keys, region or bucket name is wrong.

### Everything the service is configured with

`render.yaml` sets the rest. For reference, and if you would rather enter the settings by
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
| `AUTOX_S3_REGION` | `eu-west-1` | The Supabase project's region |
| `AUTOX_S3_BUCKET` | `autox-photos` | |
| `AUTOX_S3_PATH_STYLE` | `true` | Supabase requires it: the bucket goes in the path, on one host |
| `AUTOX_S3_CREATE_BUCKET` | `false` | The bucket exists |
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

`AUTOX_SCHEDULE_STORAGE_CHECK` is not set, so its default applies: twelve hours (see step 2).
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
   Seeded 152 demo listings, 10 demo accounts and 10 demo makes. This is invented data …
   ```

   Allow **about thirty-five minutes** after startup: a 60-listing seed took about twelve
   in a local test with Render's limits, and this one is two and a half times the size —
   152 listings and their photos, each drawn, uploaded and resized into three sizes. The
   service is live and answering throughout. The site shows the listings as they are
   written; only `/actuator/health/readiness` says 503 until the seed is done.
3. **Remove `SPRING_PROFILES_ACTIVE`** → Save, rebuild and deploy. Left on, it would only
   find the data present and do nothing — but a seeder has no business staying wired to a
   live database.

The seeded sellers' passwords are random and never shown, so nobody can sign in as them.

**If the seed fails**, the log shows `Application run failed` with the reason. It is
usually storage: a wrong key, endpoint, region or bucket name. Storage is checked before
anything is written, so a misconfiguration fails cleanly. If it fails partway regardless,
the next start reports "Demo data is already present" and skips it. Then do one deploy with
`AUTOX_DEMO_ACTION=remove` added, and one without it.

**To reset the demo later** (once visitors have filled it with test listings): deploy once
with `SPRING_PROFILES_ACTIVE=demo` **and** `AUTOX_DEMO_ACTION=remove`, then once with only
the profile (seed again), then remove both. Removal deletes exactly what the seed recorded.
Listings visitors created are left alone; remove those from the moderation screen. So are
the demo makes and models those listings use: they stay as ordinary catalogue rows, and the
next seed reuses them.

A reseed creates the demo makes and models again under new ids, and browsers keep the make
list for an hour (`Cache-Control: max-age=3600` on `/reference/makes`). For up to an hour
afterwards, a visitor who loaded the site before the reseed gets no results when searching
by make or model, until they reload with the cache cleared. Reseed when nobody is looking,
or allow that hour before showing the site.

## Step 5 — Netlify: the site

`netlify.toml` at the repository root holds the build settings: base directory
`frontend`, build command `npm run build`, publish directory `dist/frontend/browser`,
and Node 24.20.0. Netlify itself skips the build when a commit changes nothing under
`frontend/`, so a backend-only commit costs no credits. A change to `netlify.toml` alone
does not rebuild either, for the same reason.

1. app.netlify.com → **Add new project → Import an existing project → GitHub** → choose
   `YassineBalbali/autox`.
2. Netlify reads `netlify.toml`, so the build fields arrive filled in. Leave them. Set the
   **project name** you chose.
3. **Add environment variables** (on the same screen, or later under Project configuration →
   Environment variables). They are compiled into the site, because a static site has no
   server to read them at run time. Changing one means rebuilding.

   | Variable | Value | From |
   |---|---|---|
   | `AUTOX_API_BASE_URL` | `API` — e.g. `https://autox-api-kyuo.onrender.com` | Render |
   | `AUTOX_STORAGE_ORIGINS` | `S3_ENDPOINT` — the full endpoint is fine; the build keeps its origin, `https://<ref>.supabase.co` | Supabase |
   | `AUTOX_SITE_URL` | `SITE` — e.g. `https://autox-demo.netlify.app` | Netlify |

4. **Deploy.** At the end, the build log prints the API, storage and site URLs it used,
   and the security policy. Check them there.

**The build needs Java as well as Node.** The API client is generated from
`frontend/openapi.json` on every build, by OpenAPI Generator, a Java program. It is not
committed. Netlify's current build image ships Java 25. The generator this repository pins
(7.26.0) was run on Java 25 and produced the full client. If a Netlify build fails at
`api:generate` with `java: not found`, that is the cause.

`npm run build` writes two files into the published folder, and Netlify reads both:

- **`_redirects`**, containing `/*  /index.html  200`: every path with no file behind it is
  served the app, **with a 200**. A shared link to a listing therefore previews and loads,
  instead of 404ing. Netlify never lets this rule shadow a real file, so scripts,
  `robots.txt` and the preview image are served as themselves.
- **`_headers`**: the Content-Security-Policy, HSTS and the other security headers for every
  path, plus long caching for the hashed scripts and styles.

Both were checked with Netlify's own tooling: its local server answered `/annonces/…`,
`/recherche` and `/admin/moderation` with the app, a 200 and the full set of security
headers, while `robots.txt`, the preview image and the scripts were served as files.
Netlify's `_headers` parser read every rule without error.

**Credits.** The free plan has 300 credits a month. A production deploy costs 15 and
bandwidth 20 per GB; deploy previews are free. That allows about fifteen to twenty
production deploys a month. Backend-only commits do not count: their builds are skipped.
Photos are served by Supabase, not Netlify, so the site's own bandwidth is small.

## Step 6 — Close the loop

Open the site. If Netlify or Render gave you a different URL from the one you planned:

- **Render → Environment**: correct `AUTOX_CORS_ALLOWED_ORIGINS`, `AUTOX_PASSWORD_RESET_URL`
  and `AUTOX_JWT_ISSUER`, then save (it redeploys).
- **Netlify → Environment variables**: correct `AUTOX_API_BASE_URL` / `AUTOX_SITE_URL`, then
  **Deploys → Trigger deploy → Clear cache and deploy project**. A rebuild is the only way a
  build-time value changes. Check that the log ends with the `[build]` lines showing the new
  values. If it says instead that no changes were detected in the base directory, rebuild
  through a **build hook**: Project configuration → Build & deploy → Build hooks → Add, then
  `curl -X POST -d {} <hook URL>`. Netlify never skips a build a hook triggers.

Supabase needs no change: it has no origin list.

A custom domain later means the same edits, with the new origin.

## Step 7 — The uptime monitor

Render puts a free service to sleep after 15 minutes without a request. Waking it is a
full cold start, about three minutes. A monitor keeps it awake:

- **URL: `API/actuator/health/liveness`** — not `/actuator/health`. The full health check
  queries the database, so a ping every ten minutes would wake Neon every ten minutes, all
  month (see below). Liveness answers from memory.
- Interval: 10 minutes. Method GET; it answers 200 and `{"status":"UP"}`.

A month of one service always on is about 730 of the 750 free instance-hours Render gives a
workspace. Anything else free in the same workspace will run out of hours.

Keeping the API awake also keeps its twelve-hourly storage check running (step 2).

## Step 8 — Check that the rate limits cannot be dodged

Every throttle keys on the client's address: registration, sign-in, password reset and the
public read limit. Render passes requests through Cloudflare and then its own proxy.
Neither part of its `X-Forwarded-For` can be used: the front holds whatever the client
wrote, and the last untrusted entry is a Cloudflare edge that thousands of visitors share.
Render overwrites `True-Client-IP` with the real address. The API reads that header
(`AUTOX_CLIENT_IP_HEADER`), and only on connections from a private-network proxy.
`ClientAddressIntegrationTest` covers this locally; this check proves it on Render.

From a terminal (Git Bash works), with your `API`:

```bash
API=https://autox-api-kyuo.onrender.com
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

**On the live deployment** (2026-10-18), against `https://autox-api-kyuo.onrender.com`, the
answers were `201`, `409`, `409`, `409`, `429`, `429`: refused on the fifth attempt, not the
sixth, because one registration from the same address had already counted that hour. The
forged addresses changed nothing. If they had been believed, each attempt would have been
counted under a different address and none would have been refused.

If instead:

- **six answers and no `429`**, or rows for `198.51.100.x`: the forged headers are being
  believed. The limits do not hold. Stop and look at the configuration before going further.
- **one row keyed on a `10.x` or `172.x` address**: the API is not seeing `True-Client-IP`,
  and every visitor shares one bucket. Nothing can be dodged, but the limits would trip for
  everyone together. Check `AUTOX_CLIENT_IP_HEADER` is set exactly as above. If it is,
  Render's proxy is reaching the API from an address outside the private ranges Tomcat
  trusts by default. Name that address in `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`, as a
  regular expression.

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

   **The SQL Editor answers "No result"** for an `UPDATE`. That is not a failure: the
   editor shows rows, and an `UPDATE` returns none. To see that it worked, run:

   ```sql
   SELECT email, role FROM app_user WHERE email = 'you@example.com';
   ```

   It should show `ADMIN`. If it shows no row, the address does not match as typed:
   emails are stored in lower case, so type it in lower case.
3. **Sign out and in again**: the role travels in the access token. "Administration" then
   appears in the account menu, with the seeded listings waiting in the moderation queue.

## Step 10 — Check from outside

- `https://securityheaders.com/?q=SITE` — expect an A, with a Content-Security-Policy.
- Open a listing link directly in a new tab, and paste one into a messaging app. It should
  load, and the preview should show the AutoX card.
- The browser console on the home page, a listing, and the photo step of "Vendre": no
  red. The photo step is the only place the browser uploads to Supabase, so it is the real
  test of step 2's CORS check and of `AUTOX_STORAGE_ORIGINS`.

---

## Keeping Neon inside its allowance

The free plan's 100 CU-hours a month cover a 0.25 CU compute for about 400 hours. A month
has 730. **Neon must therefore sleep for most of the month**, and three things would keep it awake:

| What | How it is prevented |
|---|---|
| The connection pool pinging its open connections every two minutes | `AUTOX_DB_POOL_MIN_IDLE=0`, `AUTOX_DB_IDLE_TIMEOUT_MS=60000`: the pool empties after a minute idle |
| The background sweeps, every 10 and 15 minutes | Every 6 hours (`AUTOX_SCHEDULE_*`) |
| Health checks that query the database | Render and the uptime monitor both use `/actuator/health/liveness`, which does not |

The storage check does not touch Neon: it talks to Supabase only.

With all three in place, Neon wakes for visitors and a handful of sweeps a day. Check
**Neon → Billing/Usage** after the first week. If compute hours climb by a few each day
with no visitors, something is holding a connection open.

The first request after Neon has slept waits under a second while it resumes. That is
the price, and on a demo it is the right one.

## What would have broken, and what changed

These are the parts of the earlier configuration that would have failed, or quietly
misbehaved, on these services. Each is fixed in the repository. The list is here so the
reason for each setting is not lost.

| Problem | Fix |
|---|---|
| The API listened on `AUTOX_PORT` (8080). Render routes to `PORT` (10000) | The image passes `PORT` to the server |
| The client address came from the **first** value of `X-Forwarded-For` — client-written on Render, so every rate limit could be dodged | Tomcat's own proxy handling (`native`), trusting private-network proxies only, reading Render's `True-Client-IP` |
| Hikari's idle connections, the sweeps and a database health check would have kept Neon awake and spent its month's compute in about two weeks | The settings above |
| A free Supabase project pauses after a week without database activity, which a quiet demo can reach | A bucket check every twelve hours (step 2) |
| Without a fallback rule, every deep link (`/annonces/…`) would have been a 404 on Netlify | `npm run build` writes `_redirects` with `/*  /index.html  200` |
| The JVM's defaults in 512 MB: a heap too small for image work, and nothing bounding the rest | Memory flags in the Dockerfile, measured below |
| 200 Tomcat threads | `SERVER_TOMCAT_THREADS_MAX=20` |
| The demo seed, run against the demo's dealer cap of 3, stopped at the first dealer's fourth listing and left a half-seed that later starts skipped | The seed's own accounts are uncapped |
| The seed wrote rows before checking storage, so a storage fault failed it halfway | Storage is checked first |
| A missing variable's startup error told the operator to create a `.env` | It says to set the variable in the service's environment, then gives the local advice |

**Storage client settings, checked against Supabase.** Supabase's documentation lists
checksums (`x-amz-checksum-*`) as unsupported, and current AWS SDKs send them by default,
alongside a chunked upload encoding some S3-compatible stores reject. Both were tested
against Supabase's own storage server (`supabase/storage-api` v1.74.0, the version its
self-hosted setup pins). The test ran with the SDK version AutoX ships, over HTTPS, behind
a gateway that strips `/storage/v1` and sets `X-Forwarded-Prefix` as Supabase's gateway does:

- Put, get, head, delete and batch delete all worked, with chunked encoding on or off and
  with checksums at the default or only when required. Every object read back byte for byte.
- Presigned PUT signed over `Content-Type` and `Content-Length`, as AutoX signs it, and
  presigned GET both worked, with `/storage/v1/s3` inside the signed path.

The configuration keeps chunked encoding off, the one setting that works on every store
tried. What the local test cannot reproduce is hosted Supabase's gateway, and with it CORS;
step 2's check covers that.

**Measured locally** with Render's limits (`--memory=512m --cpus=0.1`, no `.env`, the
variables above):

- startup in 145–195 seconds;
- the full seed in about twelve minutes, at roughly twelve seconds a listing;
- memory peaking at about 340 MB of the 512 during the seed;
- the pool down to zero database connections a minute after the last request, and a
  liveness ping opening none;
- the forged-header check of step 8 answering 201, 409 ×4, 429.

## What the demo will and will not do

- **No email is sent.** "Mot de passe oublié" accepts the request and nothing arrives. The
  link is written to the API's log, which only you can read on Render. Accounts are not
  for real use, and the footer says so on every page.
- **Anything a visitor publishes is public at once**, photos included, and is reviewed
  afterwards. The caps bound how much; the moderation screen removes it. There is no
  setting that turns publishing off.
- **Cold starts.** If the monitor stops, the first visitor waits for a full start (about
  three minutes). If Neon is asleep, the first query waits about a second. If Supabase has
  paused, photos are missing until it is resumed.
- **Netlify deploy previews** (pull requests) get their own URLs. Those URLs are not in
  `AUTOX_CORS_ALLOWED_ORIGINS`, so the previews load but cannot reach the API. That is
  deliberate.
- **Swagger UI** is public at `API/swagger-ui.html`, which for a portfolio is arguably the
  point.

See `docs/weaknesses.md` for the rest, most of which matters only with real users.
