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
  cannot be pinned by tag.

> **Done since:** pinned by digest in both places, and `.github/dependabot.yml` proposes
> the next digest when Chainguard rebuilds the image. The note below is kept for why.
>
> **⚠ To pin before anyone else works on this repo.** Your call was to accept the moving tag
> while this is dev-only, which is right, but it means two developers can get different MinIO
> builds and a CI run can break with no commit behind it. Two ways to fix it when that matters:
> pin by digest (`chainguard/minio@sha256:…`, which does not move even though the tag does), or
> swap the container for LocalStack's S3, which publishes stable version tags. Neither changes
> a line of application code — both are an image reference in `docker-compose.yml` and in
> `AbstractIntegrationTest`.

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
- **Publishing requires at least one READY photo**, at your call. A pending upload does not
  count, and the refusal is a 409 with code `PHOTO_REQUIRED` so the frontend can send the
  seller back to the photo step rather than showing a generic failure.
- **No virus scanning and no image moderation.** A seller can upload anything decodable as an
  image. For a marketplace with public listings that is a real moderation surface, and it
  belongs with the admin moderation work.
- **EXIF is stripped at confirmation, originals included**, at your call. Verified against a
  real EXIF JPEG: a GPS string present in the uploaded file is absent from the object fetched
  back out of storage.

  The part worth knowing is **orientation**. Cameras write the sensor's pixels unrotated and
  record "turn this 90°" as an EXIF tag, so removing the tag without acting on it lays every
  portrait photo on its side. So the tag is read first, the rotation is applied to the pixels,
  and only then is the metadata discarded. Which forces two paths:

  - **Upright photo** — metadata segments are dropped and the compressed scan data is copied
    verbatim. Lossless; a test asserts the last 2 KB are byte-identical to the upload.
  - **Rotated photo** — the rotation has to be baked in, so the original is re-encoded, at
    quality 0.95 since that copy is what any future re-processing starts from.

  This cost a bug: `AffineTransformOp.filter` refuses to write a JPEG's native colour model
  into an `INT_RGB` destination, which only showed up on the live run because every earlier
  image test generated files with no EXIF at all, so the orientation path was never executed.
  Now covered by `ImageMetadataIntegrationTest`, which builds a JPEG with a genuine APP1
  segment.
