import { HttpClient, HttpEventType, HttpHeaders, HttpRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, concat, defer, filter, from, map, of, switchMap, take, throwError } from 'rxjs';

import { ErrorCode, ImageResponse, ListingPhotosService, PhotoRules } from '../../core/api';
import { skipAuth } from '../../core/auth/auth.service';
import { ERROR_MESSAGES_FR } from '../../core/errors/error-messages.fr';
import { ReferenceStore } from '../../core/reference/reference.store';
import { PHOTO_TYPES, photoSize } from './photo-prepare';

/** The API's limits on a photo, as `GET /api/v1/reference/photo-rules` gives them. */
export interface PhotoLimits {
  readonly maxBytes: number;
  /** Fewest pixels on the shorter edge. */
  readonly minDimension: number;
  readonly maxPerListing: number;
}

/** The limits from the API's answer, or `null` when it lacks one of them. */
export function photoLimits(rules: PhotoRules): PhotoLimits | null {
  const { maxBytes, minDimension, maxPerListing } = rules;
  if (typeof maxBytes !== 'number' || typeof minDimension !== 'number' || typeof maxPerListing !== 'number') {
    return null;
  }
  return { maxBytes, minDimension, maxPerListing };
}

/**
 * The API's limits, or `null` when they cannot be read: the checks in the browser are then
 * skipped and the API's own, which always run, decide.
 */
export function limitsFrom(reference: ReferenceStore): Observable<PhotoLimits | null> {
  return reference.photoRules().pipe(
    map(photoLimits),
    catchError(() => of(null)),
  );
}

/**
 * A photo refused in the browser, before anything was sent, for a reason the API would have
 * refused it for. Its message is the sentence the API's refusal gets, word for word.
 */
export class PhotoRefusal extends Error {
  constructor(readonly code: ErrorCode.IMAGE_TOO_LARGE | ErrorCode.IMAGE_TOO_SMALL) {
    super(ERROR_MESSAGES_FR[code]);
    this.name = 'PhotoRefusal';
  }
}

/** Where one upload has got to. */
export type UploadEvent =
  /** Bytes are going to storage. `progress` is 0 to 1. */
  | { readonly phase: 'sending'; readonly progress: number }
  /** Stored; the API is checking it and making the sizes the site displays. */
  | { readonly phase: 'processing' }
  | { readonly phase: 'done'; readonly image: ImageResponse };

/**
 * Uploads one photo to a listing, through the API's three-step flow.
 *
 * <ol>
 *   <li><b>Ask the API for a slot.</b> It answers with a pre-signed URL, valid for a few
 *       minutes, for exactly this file size and type.</li>
 *   <li><b>Send the file to that URL</b> — straight to object storage, not through the
 *       API. This is the step with progress, and the only one that carries the bytes.</li>
 *   <li><b>Tell the API it is there.</b> It decodes the image, checks it really is one,
 *       and produces the variants. Only now does the photo count.</li>
 * </ol>
 *
 * <p>Before the first step, the file is held to the API's limits ({@link refusalBeforeUpload}):
 * a photo too large or too small fails at once with a {@link PhotoRefusal}, and asks for no
 * slot.
 *
 * <p>A photo that stops after step two does not exist as far as a buyer or the "at least
 * one photo" rule is concerned, which is why a failed upload is reported as failed and
 * not as "probably fine".
 */
@Injectable({ providedIn: 'root' })
export class PhotoUploadService {
  private readonly photos = inject(ListingPhotosService);
  private readonly http = inject(HttpClient);
  private readonly reference = inject(ReferenceStore);

  upload(listingId: string, file: File): Observable<UploadEvent> {
    return limitsFrom(this.reference).pipe(
      take(1),
      switchMap((limits) => from(refusalBeforeUpload(file, limits))),
      switchMap((refusal) => (refusal ? throwError(() => refusal) : this.send(listingId, file))),
    );
  }

  private send(listingId: string, file: File): Observable<UploadEvent> {
    return this.photos
      .requestUpload({
        listingId,
        imageUploadRequest: { filename: file.name, contentType: file.type, byteSize: file.size },
      })
      .pipe(
        switchMap((ticket) => {
          const imageId = ticket.imageId;
          const url = ticket.uploadUrl;
          if (!imageId || !url) {
            throw new Error('The upload ticket carried no image id or URL.');
          }

          const send = this.http
            .request(
              new HttpRequest(ticket.method ?? 'PUT', url, file, {
                // Exactly the headers the signature was computed over. One more, or one
                // different, and storage answers 403.
                headers: new HttpHeaders(ticket.requiredHeaders ?? {}),
                reportProgress: true,
                // <b>No bearer token on this request.</b> It goes to the storage host,
                // not to the API: attaching the session's access token would hand a
                // credential to a third party and — because the URL is signed over its
                // headers — break the upload as well.
                context: skipAuth(),
              }),
            )
            .pipe(
              filter((event) => event.type === HttpEventType.UploadProgress),
              map((event): UploadEvent => {
                const progress = event as { loaded: number; total?: number };
                return {
                  phase: 'sending',
                  progress: progress.total ? Math.min(1, progress.loaded / progress.total) : 0,
                };
              }),
            );

          const confirm = defer(() =>
            concat(
              of<UploadEvent>({ phase: 'processing' }),
              this.photos
                .confirmUpload({ listingId, imageId })
                .pipe(map((image): UploadEvent => ({ phase: 'done', image }))),
            ),
          );

          // Strictly one after the other: confirming before the last byte has landed
          // answers "no uploaded file was found".
          return concat(of<UploadEvent>({ phase: 'sending', progress: 0 }), send, confirm);
        }),
      );
  }
}

/**
 * Why the API would refuse this file for its size or its resolution — as a
 * {@link PhotoRefusal} — or `null`. The resolution needs the photo decoded; one the browser
 * cannot decode is let through, for the API to judge. No limits, no checks.
 */
export async function refusalBeforeUpload(file: File, limits: PhotoLimits | null): Promise<PhotoRefusal | null> {
  if (!limits) {
    return null;
  }
  if (file.size > limits.maxBytes) {
    return new PhotoRefusal(ErrorCode.IMAGE_TOO_LARGE);
  }
  const size = await photoSize(file);
  if (size && Math.min(size.width, size.height) < limits.minDimension) {
    return new PhotoRefusal(ErrorCode.IMAGE_TOO_SMALL);
  }
  return null;
}

/**
 * Why a file cannot be uploaded, in French — or `null` when it can be tried.
 *
 * <p>Checked in the browser as soon as a file is chosen, so that a 40 MB photo is refused in
 * a millisecond with a sentence that names the file, instead of after a minute's upload with
 * a status code. The API checks all of this again; this is a courtesy, not the rule. Without
 * `limits` (not read from the API yet, or not at all) only the type and emptiness are checked.
 * The resolution is checked later, just before sending ({@link refusalBeforeUpload}).
 *
 * <p>`countOnly`: for a photo that is still to be made ready on the device, whose type
 * and size are only known afterwards.
 */
export function rejectionFor(
  file: File,
  photosSoFar: number,
  limits: PhotoLimits | null,
  options: { countOnly?: boolean } = {},
): string | null {
  if (limits && photosSoFar >= limits.maxPerListing) {
    return `« ${file.name} » n’a pas été ajoutée : une annonce contient au maximum ${limits.maxPerListing} photos.`;
  }
  if (options.countOnly) {
    return null;
  }
  if (!PHOTO_TYPES.includes(file.type)) {
    return `« ${file.name} » n’est pas une image JPEG ou PNG.`;
  }
  if (limits && file.size > limits.maxBytes) {
    return `« ${file.name} » : ${ERROR_MESSAGES_FR[ErrorCode.IMAGE_TOO_LARGE]} La taille maximale est de ${megabytes(limits.maxBytes)} Mo.`;
  }
  if (file.size === 0) {
    return `« ${file.name} » est un fichier vide.`;
  }
  return null;
}

/** "10", "2,5": a limit in megabytes, as the French write it. */
export function megabytes(bytes: number): string {
  return (Math.round((bytes / (1024 * 1024)) * 10) / 10).toString().replace('.', ',');
}
