# The test configuration shadowing, and what it was hiding

`src/test/resources/application.yml` was renamed to `application-test.yml`, so the suite now
runs against the production configuration with the test profile layered on top. This records
what the old arrangement was, what it concealed, and what turned out to be fine.

## What was wrong

Both files were called `application.yml`. Spring Boot loads `optional:classpath:/application.yml`
and takes the **first** match on the classpath; under Maven, `target/test-classes` precedes
`target/classes`. So the test file did not extend the production configuration — it
**replaced** it, entirely. Every setting production declared and the test file did not was
running on a framework default, and nothing anywhere said so.

The old file carried a comment explaining this, which made it look deliberate. It was not a
design; it was a name collision that had been rationalised.

Now, with `application-test.yml` and `@ActiveProfiles("test")`, Spring loads the main file
first and the profile file over it, **key by key**. `ConfigurationLayeringIntegrationTest`
asserts both directions: that production values reach the tests, and that the test profile's
deliberate deviations still win. The second half matters as much as the first — inheriting
everything would mean the suite had quietly stopped using its own fixtures, which is the same
class of bug in the other direction.

## What the suite was silently not testing

Ordered by how much it mattered.

### `spring.jpa.open-in-view: false` — the one with teeth

**Tests ran with open-in-view enabled. Production runs with it disabled.**

With it enabled, the persistence session stays open through response rendering, so a DTO that
lazily loads an association *during serialisation* works. With it disabled, the same code
throws `LazyInitializationException`. This is the classic Hibernate production-only failure,
and for the whole life of the project the suite was configured so that it could not catch it.

The startup warning saying so — *"spring.jpa.open-in-view is enabled by default. Therefore,
database queries may be performed during view rendering"* — was in the output of every single
test run.

**Outcome: no bug was hiding behind it.** All 228 tests pass with it correctly disabled. The
DTOs map eagerly in the service layer, and `ListingService.respond` and the search projections
were already built that way. So this was a real hole in the suite's reach rather than a real
defect in the application — but it was luck that it was empty, not design, and it is now
closed.

### `server.forward-headers-strategy: framework` — a whole behaviour was unreachable

This is the setting that makes Spring treat `X-Forwarded-For` as the client address. Both
throttles key on that address; the README has a deployment warning entirely about it; the
IPv6 `/64` masking exists because of it. **No test could reach any of it**, because the
setting lived in a file the suite never read.

`PublicRateLimitIntegrationTest.forwardedAddressIsTheKey` now covers it: three requests
carrying `X-Forwarded-For: 203.0.113.7` exhaust that address's budget, the counter row is
keyed on the forwarded address rather than the socket's, and a second forwarded address is
unaffected.

### `spring.jackson.default-property-inclusion: non_null` — assertions against the wrong bodies

Production omits null fields; the tests saw Jackson's default, which includes them. So every
response-body assertion in the suite was made against a JSON shape **the API does not
produce**. A registration-created session, for instance, really returned
`"deviceLabel": null` in tests and omits the key entirely in production.

No assertion happened to depend on the difference, so nothing broke. That is worth being
uncomfortable about rather than relieved by: `jsonPath(...).doesNotExist()` was a weaker
assertion than it looked, and any future test pinning an exact body would have been pinning a
fiction.

### `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`

Absent in tests, so Hibernate bound timestamps in the JVM's default zone (Europe/London on
this machine) rather than UTC. Benign **only** because every timestamp column is `TIMESTAMPTZ`
and every mapped field is an `Instant`, which carries no zone ambiguity. The day something is
mapped as `LocalDateTime`, this would have become an off-by-an-hour expiry bug reproducible in
exactly one of the two environments.

### `management.*` — how the problem was found

The health probe groups were configured in production and had no effect in tests. Rather than
investigate, the previous session duplicated the block into the test file to make the test
pass. That duplication worked and was the wrong fix; it is now deleted, and the block is
inherited.

### `spring.mvc.problemdetails.enabled` and `server.error.include-message/stacktrace: never`

Absent in tests. These govern responses produced by the **framework** rather than by
`ApiExceptionHandler` — an unroutable path, a malformed body, a wrong method. The application
handles most of those itself, so the blast radius was small, but "small" was not something
anyone had checked.

### Storage TTLs and region — matched by coincidence

The old test file had **no `autox.storage` block at all**. `@DynamicPropertySource` supplied
the endpoint, credentials, bucket and path-style flag from the MinIO container; everything
else — region, `upload-url-ttl`, `read-url-ttl`, `public-base-url` — was simply unset, and
came from `StorageProperties`' compact-constructor fallbacks.

Those fallbacks happen to be identical to the production YAML (`us-east-1`, 15 minutes, 6
hours). So the values agreed, by coincidence rather than by configuration. Changing a TTL in
`application.yml` would not have changed what the tests exercised.

## What turned out not to matter

Worth recording so nobody re-investigates:

- **`spring.web.locale: en` / `locale-resolver: fixed`.** Tests ran without these, yet
  `ErrorContractIntegrationTest` has always asserted English validation messages and always
  passed. The reason is `ValidationConfig`, which pins the interpolator to
  `Locale.ENGLISH` in Java. The YAML is belt and braces; the guarantee is in code.
- **`spring.flyway.validate-on-migrate: true`**, **`baseline-on-migrate: false`**, and
  **`spring.jackson.deserialization.fail-on-unknown-properties: false`** — all three restate
  a Spring Boot default, so their absence changed nothing.
- **`spring.datasource.hikari.maximum-pool-size: 10`** — also the default.
- **Batching and insert ordering** (`batch_size`, `order_inserts`, `order_updates`) —
  performance, not behaviour.

## The trap in making the change

Renaming the file in `src/` does **not** remove the copy Maven already placed in
`target/test-classes`. The first run after the rename therefore still loaded the stale
`application.yml`, and the full suite passed — against the old configuration, exactly as
before. The rename appeared to have worked and had done nothing.

`mvn clean test` fixes it. Because that is a fifteen-minute mistake to diagnose and an easy
one to repeat, `ConfigurationLayeringIntegrationTest.nothingShadowsTheProductionFile` asserts
that the `application.yml` on the classpath does not resolve inside `test-classes`, and its
failure message says what to do. CI runs `clean verify`, so it is immune by construction.

## Why the test profile now carries only deviations

The old file restated production values — TTLs, thresholds, image limits, content types — so
the two agreed by transcription, which is a form of agreement that expires. The new file
contains **only** what deliberately differs, each with a one-line reason:

| Deviation | Why |
|---|---|
| `spring.datasource.*` literals | Production's `${AUTOX_DB_*}` have no defaults and would fail to bind. Testcontainers overrides these at runtime anyway. |
| `spring.flyway.clean-disabled: false` | So a test could reset the schema. Never enabled in production. |
| `autox.auth.jwt-secret` | Production deliberately has no default. |
| `autox.auth.issuer` | A recognisable test issuer. |
| `login-throttle` delays in seconds | So escalation is observable without the suite sleeping. Thresholds are production's. |
| `autox.mail.provider: log` | Pinned, not inherited: production reads an environment variable, and a developer who has it set should not change what the suite tests. |
| `autox.mail.password-reset-url` | Asserted on in the reset-email test. |
| `rate-limit.enabled: false` | Every test shares one client address, so a suite-wide limit would couple unrelated test classes. One test turns it on for itself. |
| `particulier-active-limit: 2` | Reaches the quota cap in two calls instead of five. |
| `images.max-per-listing: 3` | Reaches the per-listing cap in three calls instead of twenty. |
| `logging.level` for containers | Quietens Testcontainers and Docker Java. |

Everything else — and there is a lot of it — now comes from the one copy that production uses.

---

## Continuous integration

`.github/workflows/backend.yml` runs `mvn -B --no-transfer-progress clean verify` on every
push to any branch, and on pull requests into `main`. Ubuntu runners ship with a working
Docker daemon, so Testcontainers needs no setup beyond being on a runner that has one.

Four choices worth naming:

- **`clean`, always.** Not thrift — correctness. The stale-resource trap above is precisely
  what an incremental CI build would reproduce, and CI is the one place where "it passed" has
  to mean something.
- **`verify`, not `test`.** It also packages the application, so a change that breaks the
  jar fails on the push that caused it rather than at deploy time.
- **An explicit `docker info` step.** It is an assertion, not setup: without a daemon every
  test in the suite fails with the same `ExceptionInInitializerError` from the container
  startup block, which says nothing about why. One line turns that into a readable failure.
- **Surefire reports uploaded on failure.** The XML is the only place the actual assertion
  failure is written down; the console log is truncated and interleaved. Without it a red run
  says "tests failed" and nothing more.

There is no path filter yet, because `backend/` is the whole repository. When `frontend/`
lands, add one so an Angular-only change does not pay for 228 backend tests.

The workflow is checked with [actionlint](https://github.com/rhysd/actionlint), which
validates the Actions schema rather than just the YAML:

```bash
docker run --rm -v "/$(pwd):/repo" -w /repo rhysd/actionlint:latest
```
