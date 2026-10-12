import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { ACCOUNT_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { FlashService } from '../../core/ui/flash.service';
import { LoginPage } from './login-page';

const BASE = 'http://api.test';

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

function authResponse() {
  return {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    expiresInSeconds: 900,
    user: { id: 'u-1', email: 'jean@exemple.fr', role: 'PARTICULIER', displayName: 'Jean' },
  };
}

/**
 * The sign-in screen.
 *
 * <p>Two things here are more than form plumbing. The return URL is attacker-chosen and has
 * to be sanitised before anything navigates to it; and the error banner has to come from
 * the French table keyed on the API's `code`, never from the API's own `detail` — which is
 * English developer text whose wording the API documents as not being a contract.
 */
describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let backend: HttpTestingController;
  let store: InMemoryTokenStore;
  let router: Router;

  async function render(url = '/connexion'): Promise<void> {
    // Reset first, so a test can re-render with a different URL. The component resolves the
    // return URL once at construction — deliberately, so it goes through safeReturnUrl
    // before anything can navigate to it — which means the URL has to be in place before
    // the component exists, and that in turn means a fresh module per URL.
    TestBed.resetTestingModule();
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

    router = TestBed.inject(Router);
    // The component reads the return URL from `router.url` at construction, so the URL has
    // to be in place before it is created.
    vi.spyOn(router, 'url', 'get').mockReturnValue(url);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginPage);
    await fixture.whenStable();
  }

  const field = (type: string) =>
    fixture.nativeElement.querySelector(`input[type="${type}"]`) as HTMLInputElement;

  const submitButton = () =>
    fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;

  async function fillAndSubmit(email = 'jean@exemple.fr', password = 'correct-horse-battery') {
    const emailInput = field('email');
    emailInput.value = email;
    emailInput.dispatchEvent(new Event('input'));

    const passwordInput = field('password');
    passwordInput.value = password;
    passwordInput.dispatchEvent(new Event('input'));

    await fixture.whenStable();
    submitButton().click();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await render();
  });

  it('signs in and stores the pair', async () => {
    await fillAndSubmit();

    const request = backend.expectOne(`${BASE}/api/v1/auth/login`);
    expect(request.request.body).toMatchObject({
      email: 'jean@exemple.fr',
      password: 'correct-horse-battery',
    });
    // Labelled, because an unlabelled session is one the user cannot recognise on the
    // sessions screen — and the label is the only handle a client has on its own entry.
    expect(request.request.body.deviceLabel).toBeTruthy();

    request.flush(authResponse());
    await fixture.whenStable();

    expect(store.tokens?.refreshToken).toBe('refresh-1');
    expect(TestBed.inject(AuthService).user()?.email).toBe('jean@exemple.fr');
  });

  it('does not submit an empty form, and reveals why', async () => {
    submitButton().click();
    await fixture.whenStable();

    backend.expectNone(`${BASE}/api/v1/auth/login`);
    // Marking the untouched controls is what makes the errors appear. Without it a submit
    // of an empty form looks like a button that does nothing.
    expect(fixture.nativeElement.querySelectorAll('.field__error').length).toBe(2);
  });

  it('trims the address, because a copied one carries a trailing space', async () => {
    await fillAndSubmit('  jean@exemple.fr  ');

    expect(backend.expectOne(`${BASE}/api/v1/auth/login`).request.body.email).toBe(
      'jean@exemple.fr',
    );
  });

  describe('after signing in', () => {
    it('returns to the page the user was sent away from', async () => {
      await render('/connexion?suivant=%2Fmon-compte%2Fsessions');
      await fillAndSubmit();
      backend.expectOne(`${BASE}/api/v1/auth/login`).flush(authResponse());
      await fixture.whenStable();

      expect(router.navigateByUrl).toHaveBeenCalledWith('/mon-compte/sessions');
    });

    it('refuses an absolute return URL, which would be phishing through our own domain', async () => {
      await render('/connexion?suivant=https%3A%2F%2Fevil.example%2Fconnexion');
      await fillAndSubmit();
      backend.expectOne(`${BASE}/api/v1/auth/login`).flush(authResponse());
      await fixture.whenStable();

      expect(router.navigateByUrl).toHaveBeenCalledWith(ACCOUNT_ROUTE);
    });

    it('goes to the account when there is nowhere to return to', async () => {
      await fillAndSubmit();
      backend.expectOne(`${BASE}/api/v1/auth/login`).flush(authResponse());
      await fixture.whenStable();

      expect(router.navigateByUrl).toHaveBeenCalledWith(ACCOUNT_ROUTE);
    });
  });

  describe('when the API refuses', () => {
    it('shows the French sentence for the code, not the API text', async () => {
      await fillAndSubmit();

      backend.expectOne(`${BASE}/api/v1/auth/login`).flush(
        {
          code: ErrorCode.INVALID_CREDENTIALS,
          // The API's own wording. It is English, untranslated, and documented as not being
          // a contract — so it must never reach the screen.
          detail: 'Email address or password is incorrect.',
        },
        { status: 401, statusText: 'Unauthorized' },
      );
      await fixture.whenStable();

      const banner = fixture.nativeElement.querySelector('.alert--error') as HTMLElement;
      expect(banner.textContent).toContain('Adresse e-mail ou mot de passe incorrect.');
      // The API's own sentence, which happens to mean the same thing and must still never
      // appear: its wording is not a contract, so matching or showing it is a trap.
      expect(banner.textContent).not.toContain('Email address or password');
    });

    it('appends the wait when the API says how long', async () => {
      await fillAndSubmit();

      backend.expectOne(`${BASE}/api/v1/auth/login`).flush(
        { code: ErrorCode.TOO_MANY_ATTEMPTS, retryAfterSeconds: 120 },
        { status: 429, statusText: 'Too Many Requests' },
      );
      await fixture.whenStable();

      const banner = fixture.nativeElement.querySelector('.alert--error') as HTMLElement;
      expect(banner.textContent).toContain('Trop de tentatives');
      expect(banner.textContent).toContain('2 minutes');
    });

    it('clears the banner as soon as the user edits the form', async () => {
      await fillAndSubmit();
      backend
        .expectOne(`${BASE}/api/v1/auth/login`)
        .flush({ code: ErrorCode.INVALID_CREDENTIALS }, { status: 401, statusText: 'x' });
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('.alert--error')).not.toBeNull();

      const passwordInput = field('password');
      passwordInput.value = 'another-attempt-entirely';
      passwordInput.dispatchEvent(new Event('input'));
      await fixture.whenStable();

      // A banner that outlives the problem it described is noise the user learns to ignore.
      expect(fixture.nativeElement.querySelector('.alert--error')).toBeNull();
    });

    it('re-enables the button, so a wrong password is not a dead end', async () => {
      await fillAndSubmit();
      expect(submitButton().disabled).toBe(true);

      backend
        .expectOne(`${BASE}/api/v1/auth/login`)
        .flush({ code: ErrorCode.INVALID_CREDENTIALS }, { status: 401, statusText: 'x' });
      await fixture.whenStable();

      expect(submitButton().disabled).toBe(false);
    });
  });

  it('shows a message handed over from another screen, once', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: new InMemoryTokenStore() },
      ],
    });

    const flash = TestBed.inject(FlashService);
    flash.set('Votre mot de passe a été modifié.');

    const page = TestBed.createComponent(LoginPage);
    await page.whenStable();

    expect(
      (page.nativeElement.querySelector('.alert--success') as HTMLElement).textContent,
    ).toContain('mot de passe a été modifié');
    // Taken, not read: left in place it would reappear the next time anyone visited.
    expect(flash.take()).toBeNull();
  });
});
