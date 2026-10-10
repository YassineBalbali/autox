# Authentication — decisions and open questions

Settled with you before building: access token 15 min, refresh token 90 days with
rotation, unverified emails may sign in, logout ends one device's session.

## What exists

| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/v1/auth/register` | none | 201 + token pair |
| POST | `/api/v1/auth/login` | none | 200 + token pair |
| POST | `/api/v1/auth/refresh` | refresh token in body | 200 + new token pair |
| POST | `/api/v1/auth/logout` | refresh token in body | 204 |
| GET | `/api/v1/me` | Bearer | own profile |
| PATCH | `/api/v1/me` | Bearer | updated profile |
| GET | `/api/v1/admin/users` | Bearer, ADMIN | paginated user list |

Error shape is RFC 9457 `application/problem+json` with a stable `code` member and,
for validation failures, a flat `errors` array.

---

## 1. Login throttling — built

Progressive delay, two scopes, state in `login_attempt_counter` (migration `V6`).
No Redis.

**Account scope**, keyed by SHA-256 of the submitted address: failures 1–4 are free,
then 30s → 1m → 2m → 4m → 8m, capped at **15 minutes**. The cap is the anti-lockout
property: an attacker failing on purpose cannot keep the real owner out for longer
than that, and a successful sign-in clears the counter outright.

**IP scope**, keyed by client address: 20 failures free, then 1m doubling to a 30m
cap. Catches a spray across many accounts from one source. A success deliberately
does **not** clear it — otherwise an attacker resets it by signing into an account
they do own. It decays on time instead.

Three properties worth naming:

- **No enumeration.** The counter is keyed on the submitted address whether or not an
  account exists, so a throttled response says nothing about whether it is
  registered. There is a test for exactly this.
- **The block precedes the password check**, so a throttled attempt costs neither a
  BCrypt hash nor a query — which is what protects the CPU during a flood.
- **Counters are written in their own transaction** (`REQUIRES_NEW`). A failed login
  ends in a thrown exception; an increment sharing that transaction would be rolled
  back and the throttle would never engage. This is the same trap that made
  refresh-token replay detection ineffective, so there is a regression test for it.

Decayed counters are swept hourly by `AuthMaintenanceSweeper` (renamed from
`LoginThrottleSweeper` once it took on expired refresh tokens and spent password reset
tokens too). Each statement is a single idempotent `DELETE` and would survive running
twice; it is coordinated across instances anyway, because "this one is safe" is a property
that quietly stops being true — see `docs/account-and-correctness-decisions.md`.

The IP scope keys IPv6 on the **/64 prefix** rather than the full address: a caller handed
a /64 can otherwise pick a fresh address per attempt and never be counted. `ClientAddress`
holds that logic and the reasoning.

**One judgement call you should check:** a throttled attempt returns **429** with
`Retry-After`, not the generic 401. You asked for "the same generic 401 regardless of
whether the account exists" — that holds for every *credential* failure, and 429 adds
nothing an attacker can use, because unknown addresses are throttled identically. The
reason to prefer 429 is that a client needs to know to back off rather than retry.
Say so if you want 401 everywhere instead; it is a two-line change.

Still not done, and worth considering later: throttling `/auth/register` (spam) and
CAPTCHA after N failures. `/auth/refresh` is already protected by replay detection.

## 2. Validation locale — pinned to English

`ValidationConfig` installs a message interpolator that forces `Locale.ENGLISH`, and
`spring.web.locale-resolver: fixed` stops `Accept-Language` affecting anything else.
The contract is now explicit: **`message` is developer-facing; clients render
end-user text from `code` and `field`**, in the user's own language.

Tested by sending the same invalid payload with `Accept-Language: fr-FR` and with
`de-DE` and asserting the two response bodies are byte-identical apart from the
timestamp.

## 3. Credentials are checked in `AuthService`, not by an `AuthenticationManager`

With one credential type and no session, Spring's provider chain adds indirection
without adding behaviour, and the explicit version is easier to read. Authorisation
of every subsequent request is still entirely Spring Security's.

The cost: if a second authentication method arrives (Google sign-in, an API key for
dealer feeds), the idiomatic structure would have been the provider chain.
Converting is contained — one service — but it is a real cost.

## 4. HS256 with a shared secret

Right while this API is both the only issuer and the only verifier. The moment a
second service must verify tokens without being able to mint them, this becomes
RSA or EC with a published JWKS. Everything goes through `JwtEncoder`/`JwtDecoder`,
so that change touches `JwtConfig` and nothing else.

Rotating `AUTOX_JWT_SECRET` invalidates every access token in flight (≤15 minutes
of disruption). Refresh tokens are unaffected — they are random, not signed.

## 5. The role travels inside the access token

`hasRole('ADMIN')` reads the `role` claim, so no database hit per request. The
consequence: a role change or a suspension takes up to 15 minutes to be felt on
access-token-authenticated calls. Refresh is checked against the database every
time, so a suspension bites within one token cycle at the latest.

If you ever need instant revocation, that is a token blocklist in Redis — real
infrastructure, and a tax on every request. I would not.

## 6. Deliberately absent

- **Email verification sending.** `email_verified_at` exists and the claim is in the
  token; nothing sets it yet. Needs an email provider and a `GET /auth/verify`
  endpoint. The decision we made — publishing requires a verified email — is
  enforced when listings are built.
- ~~**Password change and password reset.**~~ **Built later** — `POST /api/v1/me/password`
  and the `/auth/password/forgot` + `/reset` pair, both revoking every session via
  `PASSWORD_CHANGED`. What is still absent is a real mail provider: `EmailSender` has a
  logging implementation only. See `docs/account-and-correctness-decisions.md`.
- ~~**Session list / "sign out everywhere".**~~ **Built later** as
  `GET`/`DELETE /api/v1/me/sessions`, keyed on the rotation family rather than the token
  row. There is no separate "sign out everywhere": changing the password does it, and the
  session list makes one-at-a-time revocation the normal path.
- **Email change.** Has to re-verify, so it is its own endpoint, not a `PATCH /me` field.

## 7. Smaller things, flagged

- **`GET /api/v1/admin/users`** — kept, at your call. Role-based authorisation is
  exercised by something real rather than configured and untested.
- **PATCH semantics**: `null` means "leave unchanged", `""` means "clear". The gap
  is that a client cannot distinguish "not sent" from "explicitly null" without
  `JsonNullable`, which I judged not worth the dependency. Unknown members are
  ignored, so a hostile client cannot set `role` or `status` — there is a test
  asserting exactly that.
- **BCrypt cost 12** ≈ 250 ms per hash. The cost is stored in the hash, so raising
  it later leaves old hashes verifiable.
- **Login does not reveal whether an account exists**: unknown email and wrong
  password return the same code and the same wording, and the unknown-email path
  still performs a BCrypt comparison against a dummy hash so the timing matches.
- **CORS** allows `http://localhost:4200` by default, overridable with
  `AUTOX_CORS_ALLOWED_ORIGINS`. Credentials are disabled because no cookies are
  used. A native app is not subject to CORS at all.
- **Local ports**: this machine already runs a PostgreSQL on 5432 and something on
  8080, so `.env.example` defaults the database to **5433**, and your `.env` sets
  `AUTOX_PORT=8081`. Both are environment choices, not application constraints.
