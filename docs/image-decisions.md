# Image upload — decisions and open questions

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/listings/{id}/images` | reserves a slot, returns a pre-signed PUT URL, 201 |
| POST | `/api/v1/listings/{id}/images/{imageId}/confirm` | verifies the object, builds variants, idempotent |
| GET | `/api/v1/listings/{id}/images` | every photo, pending ones included |
| PUT | `/api/v1/listings/{id}/images/order` | full permutation of confirmed photos |
| DELETE | `/api/v1/listings/{id}/images/{imageId}` | removes row, variants and stored objects |

Confirmed photos also appear on the listing itself, as `images`, in gallery order — so a
client never needs a second call to render a listing.

No endpoint accepts file content. The client PUTs to storage directly; the API issues the
slot and inspects the result.

---

## 1. MinIO's official image is no longer anonymously pullable

`docker pull minio/minio` fails with *pull access denied* on a machine with working
anonymous Docker Hub access, and `quay.io/minio/minio` answers 401. Verified both, and
confirmed anonymous pulls work in general by pulling an unrelated public image.

So docker-compose and the tests both use **`chainguard/minio:latest`** — the same MinIO
server, from a registry that serves it. Two consequences:

- It runs as **root** (`user: "0"`). This image's non-root default user cannot write the
  data directory; it fails with `Unable to initialize backend: file access denied`. Fine for
  a local development container, and it is not how production storage will work.
- It ships `mc` but **no curl or wget**, so the healthcheck uses `mc ready local`.
- `:latest` is a moving tag. Chainguard's free tier does not publish version tags, so this
  cannot be pinned. **If you want reproducibility here, the alternatives are a MinIO
  registry login, or LocalStack's S3 (public, Testcontainers-supported) for the same role.**

## 2. How the three-step handshake is made safe

The interesting part is not the pre-signed URL, it is what stops it being abused.

- **Size is bound into the signature.** The slot request declares a byte count, and that
  count is signed into the URL. Uploading a different number of bytes is rejected by storage
  itself, before anything is ours. There is a test that uploads a larger file to a URL signed
  for a smaller one and asserts the PUT fails.
- **The declared content type proves nothing**, so confirmation decodes the file. A shell
  script renamed `payload.jpg` is rejected at step three, the slot is marked `FAILED`, and the
  object is deleted from the bucket. Tested.
- **Storage keys are derived, never taken from the client.** The key is
  `listings/{listingId}/{imageId}/original.jpg`. A filename containing `../` or another
  seller's id cannot influence it. The client's filename is kept only for display.
- **The photo cap counts pending slots.** Otherwise requesting slots without confirming them
  is an unbounded way around the limit.
- **A failure is recorded in its own transaction.** This is the third time this trap has
  appeared — after refresh-token replay detection and login throttling. Rejecting an upload
  means recording the rejection *and* failing the request, and doing both in one transaction
  rolls the record back. It is now a named component (`ImageFailureRecorder`) rather than an
  inline call, because the pattern clearly recurs.

## 3. Read URLs are signed and expiring, unless you set a CDN

With `AUTOX_S3_PUBLIC_BASE_URL` empty, variant URLs are pre-signed GETs valid for six hours.
That works, and it keeps the bucket private, but it has two costs: the URLs cannot be cached
by a CDN or a browser across sessions, and every listing response does signing work.

In production the right shape is a **public bucket behind a CDN**, with
`AUTOX_S3_PUBLIC_BASE_URL` set to the CDN origin — then URLs are stable, cacheable and free
to generate. The code already supports both; only the variable changes.

## 4. JPEG and PNG only, narrower than the schema allows

`listing_image`'s CHECK permits WebP and AVIF, but the configured allow-list is JPEG and PNG.
The JDK decodes those two everywhere; WebP and AVIF need a plugin (TwelveMonkeys, or
`imageio-webp`). Accepting a format we cannot resize would let a seller upload a file that
then produces no gallery.

Worth revisiting, because WebP output would cut gallery bytes by roughly 25–30% at the same
quality. That is a dependency and a decision, not a line of config. **Say if you want it.**

## 5. Variants are always JPEG, at three role-based sizes

`V8` renamed the variants from `THUMBNAIL/SMALL/MEDIUM/LARGE` to **`THUMBNAIL` / `CARD` /
`FULL`** — 320, 800 and 1600 px on the longest edge. Named after where they are shown rather
than how big they are, because a size-based name goes stale the moment a layout changes.

Downscaling is done in halving steps rather than one jump: a single pass from 4000 px to
320 px skips most source pixels and aliases visibly. Dimensions are returned with each URL so
a client can reserve layout space before the image loads.

**One known inefficiency:** a `FULL` variant can come out *larger* than the original when the
original is already small and highly compressible — the smoke test produced a 47 KB `FULL`
from a 46 KB original. It does not happen with real phone photos (3–6 MB), but the fix is
easy: skip the variant and reference the original when the original is already within the
target edge and is a JPEG. Not done, to keep every image's URL set uniform.

## 6. Orphan cleanup

`ListingImageSweeper` runs every 15 minutes over slots whose pre-signed URL has expired and
which were never confirmed. It deletes the row *and* the stored object — the case that
matters is "client uploaded but never confirmed", where the bytes are billable and referenced
by nothing. Only expired slots are touched, so an upload in flight is never pulled out from
under a client. Both behaviours are tested, including that confirmed photos are left alone.

## 7. Smaller things, flagged

- **Deletion order is database first, storage second.** If storage deletion fails, bytes
  remain that nothing references — recoverable, and the log says so. The reverse order would
  leave a row pointing at nothing, which the API would serve as a broken image.
- **Reorder requires the full set.** A partial list is rejected rather than interpreted:
  "move this to the front" and "these are the only ones I know about" are indistinguishable,
  and guessing wrong silently reorders someone's gallery. It relies on the deferred unique
  constraint on `(listing_id, position)` from `V4`, which is what lets positions be rewritten
  in place inside one transaction.
- **Gallery reads are fetch-joined.** Found while testing: without the join, rendering a
  listing with ten photos was one query for the images plus ten for their variants.
- **`ObjectStorage` is five methods wide** and mentions no vendor type. One
  implementation covers MinIO, R2, Scaleway and AWS, because they share the protocol.
- **Publishing still does not require a photo.** Now that uploads exist, this is the moment
  that check becomes reasonable — a listing with no photos is close to worthless. It is a
  one-line precondition in `ListingService.publish`. **Your call.**
- **No virus scanning and no image moderation.** A seller can upload anything decodable as an
  image. For a marketplace with public listings that is a real moderation surface, and it
  belongs with the admin moderation work.
- **No EXIF stripping.** Phone photos carry GPS coordinates, and we re-encode through
  `ImageIO`, which discards metadata as a side effect — so variants are clean, but the
  **original is stored as uploaded, with its EXIF intact**. The original is never served by
  the API, so this is not a leak today. It becomes one the moment anything serves originals.
  I would strip it on confirmation; flagging rather than assuming.
