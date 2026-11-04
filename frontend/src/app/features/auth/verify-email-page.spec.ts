import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { VerifyEmailPage } from './verify-email-page';

const BASE = 'http://api.test';
const VERIFY = `${BASE}/api/v1/auth/email/verify`;

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = null;
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

describe('VerifyEmailPage', () => {
  let backend: HttpTestingController;
  let store: InMemoryTokenStore;
  let fixture: ComponentFixture<VerifyEmailPage>;

  beforeEach(() => {
    store = new InMemoryTokenStore();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: store },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  const open = (token: string) => {
    fixture = TestBed.createComponent(VerifyEmailPage);
    fixture.componentRef.setInput('token', token);
    fixture.detectChanges();
  };
  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const settle = async () => {
    await fixture.whenStable();
    fixture.detectChanges();
  };

  it('spends the token as it opens, and says so, without ever showing it', async () => {
    open('un-jeton');
    expect(text()).toContain('Vérification en cours');

    const request = backend.expectOne(VERIFY);
    expect(request.request.body).toEqual({ token: 'un-jeton' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(text()).toContain('Votre adresse e-mail est vérifiée');
    expect(text()).not.toContain('un-jeton');
    backend.verify();
  });

  it('updates the session of someone signed in, so nothing still calls them unverified', async () => {
    store.tokens = { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 };
    const auth = TestBed.inject(AuthService);
    auth.restore();
    auth.setUser({ id: 'u-1', emailVerified: false, emailVerificationPending: true });

    open('un-jeton');
    backend.expectOne(VERIFY).flush(null, { status: 204, statusText: 'No Content' });
    backend.expectOne(`${BASE}/api/v1/me`).flush({ id: 'u-1', emailVerified: true, emailVerificationPending: false });
    await settle();

    expect(auth.user()?.emailVerified).toBe(true);
    expect(auth.user()?.emailVerificationPending).toBe(false);
  });

  it('a dead link, signed out: says so, and to sign in for a new one', async () => {
    open('perime');
    backend.expectOne(VERIFY).flush(
      { code: 'INVALID_VERIFICATION_TOKEN' },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    expect(text()).toContain('Ce lien n’est plus valable');
    expect(text()).toContain('Connectez-vous');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-action="resend"]')).toBeNull();
  });

  it('a dead link, signed in: offers a new one there and then', async () => {
    store.tokens = { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 };
    TestBed.inject(AuthService).restore();

    open('perime');
    backend.expectOne(VERIFY).flush(
      { code: 'INVALID_VERIFICATION_TOKEN' },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    expect((fixture.nativeElement as HTMLElement).querySelector('[data-action="resend"]')).not.toBeNull();
  });

  it('a link clicked again by someone already verified says there is nothing left to do', async () => {
    store.tokens = { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 };
    const auth = TestBed.inject(AuthService);
    auth.restore();
    auth.setUser({ id: 'u-1', emailVerified: true });

    open('deja-servi');
    backend.expectOne(VERIFY).flush(
      { code: 'INVALID_VERIFICATION_TOKEN' },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();

    expect(text()).toContain('votre adresse e-mail est vérifiée');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-action="resend"]')).toBeNull();
  });

  it('a link with no token asks the server nothing', async () => {
    open('  ');
    await settle();

    backend.expectNone(VERIFY);
    expect(text()).toContain('Ce lien n’est plus valable');
  });
});
