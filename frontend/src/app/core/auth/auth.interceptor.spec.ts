import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH } from '../api';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { StoredTokens, TOKEN_STORE, TokenStore } from './token-store';

/**
 * The 401-renewal path.
 *
 * <p>The test that matters here is {@link concurrent requests share one refresh}. The API
 * rotates refresh tokens on every use and treats a replayed one as theft — it revokes the
 * whole family, ending every session from that login. So a client that fires two refreshes
 * at once does not waste a round trip, it signs the user out of all their devices. That
 * makes the single in-flight refresh a correctness property, and this is the only place it
 * is enforced.
 */
describe('authInterceptor', () => {
  const BASE = 'http://api.test';

  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;
  let store: InMemoryTokenStore;
  let navigate: ReturnType<typeof vi.fn>;

  /** A store that starts signed in with an expired-looking access token. */
  class InMemoryTokenStore implements TokenStore {
    tokens: StoredTokens | null = {
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      accessTokenExpiresAt: Date.now() + 60_000,
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

  beforeEach(() => {
    store = new InMemoryTokenStore();
    navigate = vi.fn().mockResolvedValue(true);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: store },
        { provide: Router, useValue: { navigate, url: '/mes-annonces' } },
      ],
    });

    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.restore();
  });

  function authResponse(suffix: string) {
    return {
      accessToken: `access-${suffix}`,
      refreshToken: `refresh-${suffix}`,
      expiresInSeconds: 900,
      user: { id: 'u1', email: 'a@b.test', role: 'PARTICULIER', displayName: 'A' },
    };
  }

  it('attaches the access token to an API request', () => {
    http.get(`${BASE}/api/v1/me`).subscribe();

    const request = backend.expectOne(`${BASE}/api/v1/me`);
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-1');
    request.flush({});
  });

  it('sends no Authorization header when there is no session', () => {
    store.clear();
    auth.clearSession();

    http.get(`${BASE}/api/v1/listings`).subscribe();

    const request = backend.expectOne(`${BASE}/api/v1/listings`);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('never attaches a token to the public auth endpoints', () => {
    // Login with a stale bearer attached is at best confusing and at worst a 401 the
    // interceptor would then try to "renew".
    http.post(`${BASE}/api/v1/auth/login`, {}).subscribe();

    const request = backend.expectOne(`${BASE}/api/v1/auth/login`);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(authResponse('2'));
  });

  it('renews on 401 and replays the original request with the new token', () => {
    const observed: unknown[] = [];
    http.get(`${BASE}/api/v1/me`).subscribe((body) => observed.push(body));

    backend.expectOne(`${BASE}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });

    const refresh = backend.expectOne(`${BASE}/api/v1/auth/refresh`);
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh.flush(authResponse('2'));

    const replay = backend.expectOne(`${BASE}/api/v1/me`);
    expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
    replay.flush({ id: 'u1' });

    expect(observed).toEqual([{ id: 'u1' }]);
    // The rotated pair is persisted, so a reload does not present the spent token.
    expect(store.tokens).toMatchObject({ accessToken: 'access-2', refreshToken: 'refresh-2' });
  });

  it('shares one refresh across concurrent 401s', () => {
    // Three requests in flight when the access token expires. If each renewed on its own,
    // two of them would present a token the first had already rotated — which the API
    // reads as a stolen token and answers by revoking every session for the account.
    const done: string[] = [];
    http.get(`${BASE}/api/v1/me`).subscribe(() => done.push('me'));
    http.get(`${BASE}/api/v1/me/sessions`).subscribe(() => done.push('sessions'));
    http.get(`${BASE}/api/v1/listings/mine`).subscribe(() => done.push('mine'));

    for (const url of ['/api/v1/me', '/api/v1/me/sessions', '/api/v1/listings/mine']) {
      backend.expectOne(`${BASE}${url}`).flush(null, { status: 401, statusText: 'Unauthorized' });
    }

    // Exactly one. expectOne fails if there are two.
    const refresh = backend.expectOne(`${BASE}/api/v1/auth/refresh`);
    refresh.flush(authResponse('2'));

    // All three replay with the single new token.
    for (const url of ['/api/v1/me', '/api/v1/me/sessions', '/api/v1/listings/mine']) {
      const replay = backend.expectOne(`${BASE}${url}`);
      expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
      replay.flush({});
    }

    expect(done.sort()).toEqual(['me', 'mine', 'sessions']);
    backend.verify();
  });

  it('starts a new refresh for a 401 that arrives after the first one finished', () => {
    http.get(`${BASE}/api/v1/me`).subscribe();
    backend.expectOne(`${BASE}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(`${BASE}/api/v1/auth/refresh`).flush(authResponse('2'));
    backend.expectOne(`${BASE}/api/v1/me`).flush({});

    // A later 401 is a genuinely new situation: the shared slot must have been released,
    // or the application would be stuck replaying a token that is already spent.
    http.get(`${BASE}/api/v1/me`).subscribe();
    backend.expectOne(`${BASE}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });

    const second = backend.expectOne(`${BASE}/api/v1/auth/refresh`);
    expect(second.request.body).toEqual({ refreshToken: 'refresh-2' });
    second.flush(authResponse('3'));
    backend.expectOne(`${BASE}/api/v1/me`).flush({});
  });

  it('signs the user out when the refresh itself fails', () => {
    let failure: unknown;
    http.get(`${BASE}/api/v1/me`).subscribe({ error: (error: unknown) => (failure = error) });

    backend.expectOne(`${BASE}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend
      .expectOne(`${BASE}/api/v1/auth/refresh`)
      .flush({ code: 'INVALID_REFRESH_TOKEN' }, { status: 401, statusText: 'Unauthorized' });

    expect(failure).toBeInstanceOf(HttpErrorResponse);
    expect(auth.isAuthenticated()).toBe(false);
    expect(store.tokens).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/connexion'], {
      queryParams: { suivant: '/mes-annonces' },
    });
    // No replay: there is nothing left to replay with.
    backend.verify();
  });

  it('does not renew on a 401 from the refresh endpoint itself', () => {
    // Guards the loop that would otherwise be: refresh 401 -> renew -> refresh 401 -> ...
    // Against this API that loop is not merely infinite, it revokes the token family.
    let failure: unknown;
    auth.refresh().subscribe({ error: (error: unknown) => (failure = error) });

    backend
      .expectOne(`${BASE}/api/v1/auth/refresh`)
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failure).toBeInstanceOf(HttpErrorResponse);
    backend.verify();
  });

  it('does not renew on a 403, which renewing cannot fix', () => {
    let failure: unknown;
    http.get(`${BASE}/api/v1/admin/users`).subscribe({
      error: (error: unknown) => (failure = error),
    });

    backend
      .expectOne(`${BASE}/api/v1/admin/users`)
      .flush({ code: 'FORBIDDEN' }, { status: 403, statusText: 'Forbidden' });

    expect(failure).toBeInstanceOf(HttpErrorResponse);
    expect(auth.isAuthenticated()).toBe(true);
    backend.verify();
  });

  it('does not attempt a renewal with no refresh token', () => {
    store.clear();
    auth.clearSession();

    let failure: unknown;
    http.get(`${BASE}/api/v1/me`).subscribe({ error: (error: unknown) => (failure = error) });
    backend.expectOne(`${BASE}/api/v1/me`).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failure).toBeInstanceOf(HttpErrorResponse);
    backend.verify();
  });
});
