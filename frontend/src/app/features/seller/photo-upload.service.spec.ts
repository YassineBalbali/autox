import { HttpEventType, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { AuthService } from '../../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE, TokenStore } from '../../core/auth/token-store';
import { PHOTO_RULES, PhotoUploadService, UploadEvent, rejectionFor } from './photo-upload.service';

const BASE = 'http://api.test';
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

  it('asks for a slot, sends the file to storage, then confirms — in that order', () => {
    const photo = file();
    const run = start(photo);

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

  it('sends no bearer token to storage', () => {
    start();
    const ticket = backend.expectOne(`${BASE}/api/v1/listings/l1/images`);
    // The API call is authenticated…
    expect(ticket.request.headers.get('Authorization')).toBe('Bearer access-1');
    ticket.flush(TICKET);

    // …and the upload is not: another host, and a signature computed over its headers.
    const put = backend.expectOne(STORAGE);
    expect(put.request.headers.has('Authorization')).toBe(false);
  });

  it('sends exactly the headers the ticket requires', () => {
    start();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush({
      ...TICKET,
      requiredHeaders: { 'Content-Type': 'image/jpeg', 'x-amz-meta-listing': 'l1' },
    });

    const put = backend.expectOne(STORAGE);
    expect(put.request.headers.keys().sort()).toEqual(['Content-Type', 'x-amz-meta-listing']);
    expect(put.request.headers.get('x-amz-meta-listing')).toBe('l1');
  });

  it('reports progress as a fraction while the bytes go, then that the image is being checked', () => {
    const run = start();
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

  it('never reports more than complete, and copes with an unknown total', () => {
    const run = start();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    const put = backend.expectOne(STORAGE);

    put.event({ type: HttpEventType.UploadProgress, loaded: 100 });
    put.event({ type: HttpEventType.UploadProgress, loaded: 4096, total: 2048 });

    expect(run.events.slice(1)).toEqual([
      { phase: 'sending', progress: 0 },
      { phase: 'sending', progress: 1 },
    ]);
  });

  it('fails, and uploads nothing, when the API refuses the slot', () => {
    const run = start();
    backend
      .expectOne(`${BASE}/api/v1/listings/l1/images`)
      .flush({ code: ErrorCode.TOO_MANY_IMAGES }, { status: 409, statusText: 'Conflict' });

    expect(run.failure()).toBeTruthy();
    backend.expectNone(STORAGE);
  });

  it('fails without confirming when storage refuses the file', () => {
    // A photo that never reached storage must not be confirmed as though it had.
    const run = start();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    backend.expectOne(STORAGE).flush('expired', { status: 403, statusText: 'Forbidden' });

    expect(run.failure()).toBeTruthy();
    expect(run.events.some((event) => event.phase === 'done')).toBe(false);
    backend.expectNone(`${BASE}/api/v1/listings/l1/images/i1/confirm`);
  });

  it('fails when the API rejects the image at confirmation', () => {
    // Stored, but not an image the site can use — too small, or not really a JPEG.
    const run = start();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush(TICKET);
    backend.expectOne(STORAGE).flush(null);
    backend
      .expectOne(`${BASE}/api/v1/listings/l1/images/i1/confirm`)
      .flush({ code: ErrorCode.IMAGE_TOO_SMALL }, { status: 422, statusText: 'Unprocessable' });

    expect(run.failure()).toBeTruthy();
    expect(run.events.at(-1)).toEqual({ phase: 'processing' });
  });

  it('fails when the ticket carries no address to send to', () => {
    const run = start();
    backend.expectOne(`${BASE}/api/v1/listings/l1/images`).flush({ imageId: 'i1' });

    expect(run.failure()).toBeInstanceOf(Error);
  });

  describe('rejectionFor', () => {
    it('lets a JPEG or a PNG within the limits through', () => {
      expect(rejectionFor(file('a.jpg', 'image/jpeg'), 0)).toBeNull();
      expect(rejectionFor(file('a.png', 'image/png'), 19)).toBeNull();
    });

    it('refuses anything else by name, in French', () => {
      expect(rejectionFor(file('notes.pdf', 'application/pdf'), 0)).toBe(
        '« notes.pdf » n’est pas une image JPEG ou PNG.',
      );
      // What a phone produces by default, and the likeliest wrong file of all.
      expect(rejectionFor(file('IMG_1.heic', 'image/heic'), 0)).toContain('IMG_1.heic');
    });

    it('refuses a file over the size limit, saying how big it is and what the limit is', () => {
      const big = file('grande.jpg', 'image/jpeg', PHOTO_RULES.maxBytes + 1);
      expect(rejectionFor(big, 0)).toBe(
        '« grande.jpg » est trop volumineuse (11 Mo). La taille maximale est de 10 Mo.',
      );
      expect(rejectionFor(file('juste.jpg', 'image/jpeg', PHOTO_RULES.maxBytes), 0)).toBeNull();
    });

    it('refuses an empty file', () => {
      expect(rejectionFor(file('vide.jpg', 'image/jpeg', 0), 0)).toBe('« vide.jpg » est un fichier vide.');
    });

    it('refuses the photo that would be one too many', () => {
      expect(rejectionFor(file('une-de-trop.jpg'), PHOTO_RULES.maxPerListing)).toBe(
        '« une-de-trop.jpg » n’a pas été ajoutée : une annonce contient au maximum 20 photos.',
      );
    });
  });
});
