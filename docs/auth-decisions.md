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

## 1. There is no rate limiting, and auth endpoints need it

This is the most important gap in what I just built. `/auth/login` will accept
unlimited attempts, so a 12-character password policy and BCrypt cost 12 are the
only things standing between an attacker and a weak password. BCrypt makes each
guess expensive for *us* too — a login flood is also a cheap way to saturate the
CPU.

What it needs, in rough order of value:

1. **Per-account attempt throttling**: a counter plus a lockout window, or
   exponential backoff. Needs a column or a small table.
2. **Per-IP limiting** on `/auth/*`, via Bucket4j in the app or at the reverse
   proxy / CDN. The proxy is the better place — it protects the JVM from the flood
   rather than absorbing it.
3. **CAPTCHA** after N failures, if abuse becomes real.

It was not in the brief, so I did not build it. **Tell me if you want it before the
listing endpoints, because retrofitting lockout state onto a live user table is
more annoying than adding it now.**

## 2. Validation messages come back in mixed languages

Bean Validation's built-in constraints resolve their messages from the JVM's
locale, so on your machine the API returns a French "ne doit pas être vide" next to
my own English "must be between 12 and 128 characters". The *shape* is consistent;
the language is not.

Three ways out:

- **A — treat messages as developer-facing** and have clients render their own text
  from `code` and `field`. This is already how the contract is designed, and it is
  what a multi-locale app has to do anyway.
- **B — pin the server to English** (`spring.messages` + a fixed locale) so the API
  is uniform and the Angular app localises everything.
- **C — real i18n**: a `ValidationMessages_fr.properties` bundle plus
  `Accept-Language` resolution, returning fully localised messages per request.

I would do **A now and C later**. Currently it is accidental, which is worse than
any of the three. **Your call.**

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
- **Password change and password reset.** Both need to revoke every other session
  (`revokeAllForUser` with `PASSWORD_CHANGED` is already there for it), and reset
  needs email. Not in the brief; say when.
- **Session list / "sign out everywhere".** `refresh_token` records device label,
  user agent and IP for exactly this screen, and `revokeAllSessions` exists.
  No endpoint exposes it yet.
- **Email change.** Has to re-verify, so it is its own endpoint, not a `PATCH /me` field.

## 7. Smaller things, flagged

- **`GET /api/v1/admin/users` was not on your list.** I added it so role-based
  authorisation is exercised by something real rather than configured and
  untested — three of the integration tests depend on it. Delete it if you would
  rather wait for the moderation endpoints; the authz configuration stays valid.
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
