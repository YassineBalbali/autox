import { HttpClient, HttpEventType, HttpHeaders, HttpRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, concat, defer, filter, map, of, switchMap } from 'rxjs';

import { ImageResponse, ListingPhotosService } from '../../core/api';
import { skipAuth } from '../../core/auth/auth.service';

/** Mirrors of `autox.listing.images` on the backend. The API enforces them regardless. */
export const PHOTO_RULES = {
  contentTypes: ['image/jpeg', 'image/png'],
  maxBytes: 10 * 1024 * 1024,
  maxPerListing: 20,
} as const;

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
 * <p>A photo that stops after step two does not exist as far as a buyer or the "at least
 * one photo" rule is concerned, which is why a failed upload is reported as failed and
 * not as "probably fine".
 */
@Injectable({ providedIn: 'root' })
export class PhotoUploadService {
  private readonly photos = inject(ListingPhotosService);
  private readonly http = inject(HttpClient);

  upload(listingId: string, file: File): Observable<UploadEvent> {
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
 * Why a file cannot be uploaded, in French — or `null` when it can be tried.
 *
 * <p>Checked in the browser before anything is sent, so that a 40 MB photo is refused in
 * a millisecond with a sentence that names the file, instead of after a minute's upload
 * with a status code. The API checks all of this again; this is a courtesy, not the rule.
 */
export function rejectionFor(file: File, photosSoFar: number): string | null {
  if (photosSoFar >= PHOTO_RULES.maxPerListing) {
    return `« ${file.name} » n’a pas été ajoutée : une annonce contient au maximum ${PHOTO_RULES.maxPerListing} photos.`;
  }
  if (!(PHOTO_RULES.contentTypes as readonly string[]).includes(file.type)) {
    return `« ${file.name} » n’est pas une image JPEG ou PNG.`;
  }
  if (file.size > PHOTO_RULES.maxBytes) {
    const megabytes = Math.ceil(file.size / (1024 * 1024));
    return `« ${file.name} » est trop volumineuse (${megabytes} Mo). La taille maximale est de 10 Mo.`;
  }
  if (file.size === 0) {
    return `« ${file.name} » est un fichier vide.`;
  }
  return null;
}
