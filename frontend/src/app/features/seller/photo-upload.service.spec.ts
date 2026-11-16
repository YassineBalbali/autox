import { HttpEventType, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { AuthService } from '../../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE, TokenStore } from '../../core/auth/token-store';
import LABELS from '../../../../labels.fr.json';
import {
  PhotoLimits,
  PhotoRefusal,
  PhotoUploadService,
  UploadEvent,
  megabytes,
  photoLimits,
  rejectionFor,
} from './photo-upload.service';

const BASE = 'http://api.test';
const RULES = `${BASE}/api/v1/reference/photo-rules`;
/** What the API answers today; the tests below only ever use these through the API's answer. */
const RULES_BODY = { contentTypes: ['image/jpeg', 'image/png'], maxBytes: 10 * 1024 * 1024, minDimension: 400, maxPerListing: 20 };
const LIMITS = photoLimits(RULES_BODY) as PhotoLimits;
const STORAGE = 'http://storage.test/autox-images/listings/l1/i1/original?X-Amz-Signature=abc';

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    accessTokenExpiresAt: Date.now() + 900_000,
  };
  read() {
    return this.tokens;
  }
  write(tokens: StoredTokens) {
    this.tokens = tokens;
  }
  clear() {
    this.tokens = null;
  }
}

/**
 * The three-step upload.
 *
 * <p>The test that matters is {@link sends no bearer token to storage}. The real
 * interceptor is installed here for that reason: the pre-signed URL points at another
 * host, and the session's access token must never travel to it.
 */
describe('PhotoUploadService', () => {
  let service: PhotoUploadService;
  let backend: HttpTestingController;

  const file = (name = 'voiture.jpg', type = 'image/jpeg', size = 2048) =>
    new File([new Uint8Array(size)], name, { type });

  const TICKET = {
    imageId: 'i1',
    uploadUrl: STORAGE,
    method: 'PUT',
    requiredHeaders: { 'Content-Type': 'image/jpeg' },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: new InMemoryTokenStore() },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true), url: '/vendre' } },
      ],
    });
    TestBed.inject(AuthService).restore();
    service = TestBed.inject(PhotoUploadService);
    backend = TestBed.inject(HttpTestingController);
  });

  /** Answers the limits request an upload starts with, and lets the check before sending run. */
  async function rules(answer: object | null = RULES_BODY): Promise<void> {
    const request = backend.expectOne(RULES);
    if (answer) {
      request.flush(answer);
    } else {
      request.flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'Error' });
    }
    await new Promise((resolve) => setTimeout(resolve));
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function start(photo = file()) {
    const events: UploadEvent[] = [];
    let failure: unknown = null;
    let complete = false;
    service.upload('l1', photo).subscribe({
      next: (event) => events.push(event),
      error: (error: unknown) => (failure = error),
      complete: () => (complete = true),
    });
    return { events, failure: () => failure, complete: () => complete };
  }

  it('asks for a slot, sends the file to storage, then confirms — in that order', async () => {
    const photo = file();
    const run = start(photo);
    await rules();

    // 1. The slot: what the file is, never the file.
    const ticket = backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    expect(ticket.request.method).toBe('POST');
    expect(ticket.request.body).toEqual({ filename: 'voiture.jpg', contentType: 'image/jpeg', byteSize: 2048 });
    // Nothing else has been attempted yet.
    backend.expectNone(STORAGE);
    ticket.flush(TICKET);

    // 2. The bytes, straight to storage.
    const put = backend.expectOne(STORAGE);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toBe(photo);
    // Confirming before the last byte has landed answers "no uploaded file was found".
    backend.expectNone(`${BASE}/api/v1/listings/l1/images/i1/confirm`);
    put.flush(null);

    // 3. The confirmation.
    const confirm = backend.expectOne(`${BASE}/api/v1/listings/l1/images/i1/confirm`);
    expect(confirm.request.method).toBe('POST');
    confirm.flush({ id: 'i1', position: 0, status: 'READY' });

    expect(run.events.at(-1)).toEqual({ phase: 'done', image: { id: 'i1', position: 0, status: 'READY' } });
    expect(run.complete()).toBe(true);
    backend.verify();
  });

  it('sends no bearer token to storage', async () => {
    start();
    await rules();
    const ticket = backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    // The API call is authenticated…
    expect(ticket.request.headers.get('Authorization')).toBe('Bearer access-1');
    ticket.flush(TICKET);

    // …and the upload is not: another host, and a signature computed over its headers.
    const put = backend.expectOne(STORAGE);
    expect(put.request.headers.has('Authorization')).toBe(false);
  });

  it('sends exactly the headers the ticket requires', async () => {
    start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush({
      ...TICKET,
      requiredHeaders: { 'Content-Type': 'image/jpeg', 'x-amz-meta-listing': 'l1' },
    });

    const put = backend.expectOne(STORAGE);
    expect(put.request.headers.keys().sort()).toEqual(['Content-Type', 'x-amz-meta-listing']);
    expect(put.request.headers.get('x-amz-meta-listing')).toBe('l1');
  });

  it('reports progress as a fraction while the bytes go, then that the image is being checked', async () => {
    const run = start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    const put = backend.expectOne(STORAGE);

    put.event({ type: HttpEventType.UploadProgress, loaded: 512, total: 2048 });
    put.event({ type: HttpEventType.UploadProgress, loaded: 2048, total: 2048 });
    put.flush(null);

    expect(run.events).toEqual([
      { phase: 'sending', progress: 0 },
      { phase: 'sending', progress: 0.25 },
      { phase: 'sending', progress: 1 },
      { phase: 'processing' },
    ]);
  });

  it('never reports more than complete, and copes with an unknown total', async () => {
    const run = start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    const put = backend.expectOne(STORAGE);

    put.event({ type: HttpEventType.UploadProgress, loaded: 100 });
    put.event({ type: HttpEventType.UploadProgress, loaded: 4096, total: 2048 });

    expect(run.events.slice(1)).toEqual([
      { phase: 'sending', progress: 0 },
      { phase: 'sending', progress: 1 },
    ]);
  });

  it('fails, and uploads nothing, when the API refuses the slot', async () => {
    const run = start();
    await rules();
    backend
      .expectOne(`${BASE}/api/v1/listings/l1/images`)
      .flush({ code: ErrorCode.TOO_MANY_IMAGES }, { status: 409, statusText: 'Conflict' });

    expect(run.failure()).toBeTruthy();
    backend.expectNone(STORAGE);
  });

  it('fails without confirming when storage refuses the file', async () => {
    // A photo that never reached storage must not be confirmed as though it had.
    const run = start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    backend.expectOne(STORAGE).flush('expired', { status: 403, statusText: 'Forbidden' });

    expect(run.failure()).toBeTruthy();
    expect(run.events.some((event) => event.phase === 'done')).toBe(false);
    backend.expectNone(`${BASE}/api/v1/listings/l1/images/i1/confirm`);
  });

  it('fails when the API rejects the image at confirmation', async () => {
    // Stored, but not an image the site can use — too small, or not really a JPEG.
    const run = start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    backend.expectOne(STORAGE).flush(null);
    backend
      .expectOne(`${BASE}/api/v1/listings/l1/images/i1/confirm`)
      .flush({ code: ErrorCode.IMAGE_TOO_SMALL }, { status: 422, statusText: 'Unprocessable' });

    expect(run.failure()).toBeTruthy();
    expect(run.events.at(-1)).toEqual({ phase: 'processing' });
  });

  it('fails when the ticket carries no address to send to', async () => {
    const run = start();
    await rules();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush({ imageId: 'i1' });

    expect(run.failure()).toBeInstanceOf(Error);
  });

  describe('the API’s limits, before anything is sent', () => {
    it('refuses a file over the size limit without asking for a slot, in the words of the API’s refusal', async () => {
      const run = start(file('grande.jpg', 'image/jpeg', RULES_BODY.maxBytes + 1));
      await rules();

      expect(run.failure()).toBeInstanceOf(PhotoRefusal);
      expect((run.failure() as PhotoRefusal).code).toBe(ErrorCode.IMAGE_TOO_LARGE);
      expect((run.failure() as PhotoRefusal).message).toBe(LABELS.enums.ErrorCode.IMAGE_TOO_LARGE);
      backend.expectNone(`${BASE}/api/v1/listings/l1/images`);
    });

    it('refuses a photo under the minimum on its shorter edge, whichever way up', async () => {
      vi.stubGlobal('createImageBitmap', async () => ({ width: 1200, height: 399, close: () => undefined }));
      const run = start();
      await rules();

      expect(run.failure()).toBeInstanceOf(PhotoRefusal);
      expect((run.failure() as PhotoRefusal).code).toBe(ErrorCode.IMAGE_TOO_SMALL);
      expect((run.failure() as PhotoRefusal).message).toBe(LABELS.enums.ErrorCode.IMAGE_TOO_SMALL);
      backend.expectNone(`${BASE}/api/v1/listings/l1/images`);
    });

    it('sends a photo exactly at the minimum', async () => {
      vi.stubGlobal('createImageBitmap', async () => ({ width: 400, height: 600, close: () => undefined }));
      start();
      await rules();

      backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    });

    it('sends a photo the browser cannot decode, for the API to judge', async () => {
      vi.stubGlobal('createImageBitmap', async () => {
        throw new DOMException('undecodable', 'InvalidStateError');
      });
      start();
      await rules();

      backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    });

    it('takes the limits from the API, not from the site', async () => {
      vi.stubGlobal('createImageBitmap', async () => ({ width: 700, height: 500, close: () => undefined }));
      const run = start();
      await rules({ ...RULES_BODY, minDimension: 600 });

      expect((run.failure() as PhotoRefusal).code).toBe(ErrorCode.IMAGE_TOO_SMALL);
    });

    it('still uploads when the limits cannot be read: the API checks regardless', async () => {
      start(file('grande.jpg', 'image/jpeg', RULES_BODY.maxBytes + 1));
      await rules(null);

      backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    });
  });

  describe('rejectionFor', () => {
    it('lets a JPEG or a PNG within the limits through', () => {
      expect(rejectionFor(file('a.jpg', 'image/jpeg'), 0, LIMITS)).toBeNull();
      expect(rejectionFor(file('a.png', 'image/png'), 19, LIMITS)).toBeNull();
    });

    it('refuses anything else by name, in French', () => {
      expect(rejectionFor(file('notes.pdf', 'application/pdf'), 0, LIMITS)).toBe(
        '« notes.pdf » n’est pas une image JPEG ou PNG.',
      );
      // What a phone produces by default, and the likeliest wrong file of all.
      expect(rejectionFor(file('IMG_1.heic', 'image/heic'), 0, LIMITS)).toContain('IMG_1.heic');
    });

    it('refuses a file over the size limit, saying how big it is and what the limit is', () => {
      const big = file('grande.jpg', 'image/jpeg', LIMITS.maxBytes + 1);
      expect(rejectionFor(big, 0, LIMITS)).toBe(
        `« grande.jpg » : ${LABELS.enums.ErrorCode.IMAGE_TOO_LARGE} La taille maximale est de 10 Mo.`,
      );
      expect(rejectionFor(file('juste.jpg', 'image/jpeg', LIMITS.maxBytes), 0, LIMITS)).toBeNull();
    });

    it('checks only the type and emptiness while the API’s limits are unknown', () => {
      expect(rejectionFor(file('grande.jpg', 'image/jpeg', LIMITS.maxBytes + 1), 0, null)).toBeNull();
      expect(rejectionFor(file('une-de-plus.jpg'), 500, null)).toBeNull();
      expect(rejectionFor(file('notes.pdf', 'application/pdf'), 0, null)).toContain('n’est pas une image JPEG ou PNG');
    });

    it('writes a limit in megabytes as the French do', () => {
      expect(megabytes(10 * 1024 * 1024)).toBe('10');
      expect(megabytes(2.5 * 1024 * 1024)).toBe('2,5');
    });

    it('refuses an empty file', () => {
      expect(rejectionFor(file('vide.jpg', 'image/jpeg', 0), 0, LIMITS)).toBe('« vide.jpg » est un fichier vide.');
    });

    it('refuses the photo that would be one too many', () => {
      expect(rejectionFor(file('une-de-trop.jpg'), LIMITS.maxPerListing, LIMITS)).toBe(
        '« une-de-trop.jpg » n’a pas été ajoutée : une annonce contient au maximum 20 photos.',
      );
    });
  });
});
