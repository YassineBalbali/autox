import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { FlashService } from '../../core/ui/flash.service';
import { ForgotPasswordPage } from './forgot-password-page';
import { ResetPasswordPage } from './reset-password-page';

const BASE = 'http://api.test';

class InMemoryTokenStore implements TokenStore {
  constructor(public tokens: StoredTokens | null = null) {}
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

function configure(store = new InMemoryTokenStore()) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: BASE_PATH, useValue: BASE },
      { provide: TOKEN_STORE, useValue: store },
    ],
  });
  return store;
}

function byLabel(fixture: ComponentFixture<unknown>, text: string): HTMLInputElement {
  const label = (
    Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[]
  ).find((candidate) => candidate.textContent?.includes(text));
  if (!label) {
    throw new Error(`No field labelled "${text}".`);
  }
  return fixture.nativeElement.querySelector(`#${label.getAttribute('for')}`);
}

async function type(
  fixture: ComponentFixture<unknown>,
  label: string,
  value: string,
): Promise<void> {
  const element = byLabel(fixture, label);
  element.value = value;
  element.dispatchEvent(new Event('input'));
  await fixture.whenStable();
}

async function submit(fixture: ComponentFixture<unknown>): Promise<void> {
  (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
  await fixture.whenStable();
}

/**
 * Asking for a reset link.
 *
 * <p>The property under test is that the screen says the same thing whether or not the
 * address has an account. The API answers identically by design; a screen that added
 * "aucun compte avec cette adresse" would undo that entirely and turn the form into a way
 * to check whether a given person is a customer.
 */
describe('ForgotPasswordPage', () => {
  let fixture: ComponentFixture<ForgotPasswordPage>;
  let backend: HttpTestingController;

  beforeEach(async () => {
    configure();
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ForgotPasswordPage);
    await fixture.whenStable();
  });

  it('asks the API and confirms, without the link carrying a bearer token', async () => {
    await type(fixture, 'Adresse e-mail', 'jean@exemple.fr');
    await submit(fixture);

    const request = backend.expectOne(`${BASE}/api/v1/auth/password/forgot`);
    expect(request.request.body).toEqual({ email: 'jean@exemple.fr' });
    // The endpoint is reached by somebody who cannot sign in, so there is nothing to
    // attach — and a 401 here must never start a token renewal.
    expect(request.request.headers.has('Authorization')).toBe(false);

    request.flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.alert--success')).not.toBeNull();
  });

  it('replaces the form, rather than inviting a second, throttled submit', async () => {
    await type(fixture, 'Adresse e-mail', 'jean@exemple.fr');
    await submit(fixture);
    backend
      .expectOne(`${BASE}/api/v1/auth/password/forgot`)
      .flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it('commits to nothing about whether the account exists', async () => {
    await type(fixture, 'Adresse e-mail', 'nobody@exemple.fr');
    await submit(fixture);
    backend
      .expectOne(`${BASE}/api/v1/auth/password/forgot`)
      .flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Si un compte');
    // And it says so openly, because the user who simply mistyped their address gets this
    // same message and needs to know that silence is not confirmation.
    expect(text).toContain('nous ne pouvons pas vous dire');
  });

  it('surfaces throttling, which is the one failure worth reporting here', async () => {
    await type(fixture, 'Adresse e-mail', 'jean@exemple.fr');
    await submit(fixture);

    backend.expectOne(`${BASE}/api/v1/auth/password/forgot`).flush(
      { code: ErrorCode.TOO_MANY_ATTEMPTS, retryAfterSeconds: 600 },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await fixture.whenStable();

    // Hiding this behind the generic confirmation would promise an email that is not coming.
    const banner = fixture.nativeElement.querySelector('.alert--error') as HTMLElement;
    expect(banner.textContent).toContain('10 minutes');
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });

  it('will not submit an address that is not one', async () => {
    await type(fixture, 'Adresse e-mail', 'jean.exemple.fr');
    await submit(fixture);

    backend.expectNone(`${BASE}/api/v1/auth/password/forgot`);
  });
});

/**
 * Spending the link.
 *
 * <p>Two behaviours carry weight. The token is never rendered — it is a bearer credential,
 * and on screen it is in a screenshot and in a support ticket. And a successful reset
 * revokes every session on the account, so whatever is held locally is already dead and has
 * to be cleared rather than left to fail on the next request.
 */
describe('ResetPasswordPage', () => {
  let fixture: ComponentFixture<ResetPasswordPage>;
  let backend: HttpTestingController;
  let store: InMemoryTokenStore;
  let router: Router;

  async function render(token: string): Promise<void> {
    store = configure(
      new InMemoryTokenStore({
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        accessTokenExpiresAt: Date.now() + 900_000,
      }),
    );

    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    TestBed.inject(AuthService).restore();

    fixture = TestBed.createComponent(ResetPasswordPage);
    fixture.componentRef.setInput('token', token);
    await fixture.whenStable();
  }

  async function fillAndSubmit(): Promise<void> {
    await type(fixture, 'Nouveau mot de passe', 'a-brand-new-passphrase');
    await type(fixture, 'Confirmer le mot de passe', 'a-brand-new-passphrase');
    await submit(fixture);
  }

  it('spends the token from the link', async () => {
    await render('tok-abc');
    await fillAndSubmit();

    expect(backend.expectOne(`${BASE}/api/v1/auth/password/reset`).request.body).toEqual({
      token: 'tok-abc',
      newPassword: 'a-brand-new-passphrase',
    });
  });

  it('never puts the token on the page', async () => {
    await render('tok-abc');
    expect(fixture.nativeElement.innerHTML).not.toContain('tok-abc');
  });

  it('clears the local session, because the server has revoked it', async () => {
    await render('tok-abc');
    await fillAndSubmit();
    backend
      .expectOne(`${BASE}/api/v1/auth/password/reset`)
      .flush(null, { status: 204, statusText: '' });
    await fixture.whenStable();

    // Leaving the tokens in place would mean a user who reset their password because the
    // account was compromised is still carrying the credentials from before.
    expect(store.tokens).toBeNull();
  });

  it('hands the outcome to the sign-in screen', async () => {
    await render('tok-abc');
    await fillAndSubmit();
    backend
      .expectOne(`${BASE}/api/v1/auth/password/reset`)
      .flush(null, { status: 204, statusText: '' });
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith(SIGN_IN_ROUTE);
    // Through the flash service rather than a query parameter, so a reload does not repeat
    // the message and a shared link does not carry it.
    expect(TestBed.inject(FlashService).take()).toContain('mot de passe a été modifié');
  });

  it('offers a new link when this one has been spent', async () => {
    await render('tok-abc');
    await fillAndSubmit();

    backend
      .expectOne(`${BASE}/api/v1/auth/password/reset`)
      .flush({ code: ErrorCode.INVALID_RESET_TOKEN }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    const banner = fixture.nativeElement.querySelector('.alert--error') as HTMLElement;
    expect(banner.textContent).toContain('n’est plus valable');
    // A dead end here is a user who cannot recover their account at all.
    expect(banner.querySelector('a')?.getAttribute('href')).toBe('/mot-de-passe-oublie');
  });

  it('explains a link with no token instead of showing a form that cannot work', async () => {
    // Mail clients truncate long links. The form would submit an empty token and be
    // refused, which reads as "my new password was rejected".
    await render('');

    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('lien est incomplet');
  });
});
