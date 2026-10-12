import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { ChangePasswordPage } from './change-password-page';

const BASE = 'http://api.test';

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = {
    accessToken: 'access-old',
    refreshToken: 'refresh-old',
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
 * Changing a password, and the token reissue that comes with it.
 *
 * <p><b>The first test is the reason this file exists.</b> The API revokes every refresh
 * token family on the account — the point of the operation — and returns a fresh pair in
 * the same response. A screen that ignored that body would hold tokens the server has just
 * revoked: the change would appear to work, and the user would be signed out at the next
 * renewal, minutes later, with nothing on screen connecting the two events.
 */
describe('ChangePasswordPage', () => {
  let fixture: ComponentFixture<ChangePasswordPage>;
  let backend: HttpTestingController;
  let store: InMemoryTokenStore;
  let auth: AuthService;

  beforeEach(async () => {
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
    auth = TestBed.inject(AuthService);
    auth.restore();

    fixture = TestBed.createComponent(ChangePasswordPage);
    await fixture.whenStable();
  });

  function byLabel(text: string): HTMLInputElement {
    const label = (
      Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[]
    ).find((candidate) => candidate.textContent?.includes(text));
    if (!label) {
      throw new Error(`No field labelled "${text}".`);
    }
    return fixture.nativeElement.querySelector(`#${label.getAttribute('for')}`);
  }

  async function type(label: string, value: string): Promise<void> {
    const element = byLabel(label);
    element.value = value;
    element.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function fillAndSubmit(current = 'correct-horse-battery'): Promise<void> {
    await type('Mot de passe actuel', current);
    await type('Nouveau mot de passe', 'a-brand-new-passphrase');
    await type('Confirmer le nouveau mot de passe', 'a-brand-new-passphrase');
    (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
  }

  const newPair = {
    accessToken: 'access-new',
    refreshToken: 'refresh-new',
    expiresInSeconds: 900,
    user: { id: 'u-1', email: 'jean@exemple.fr', role: 'PARTICULIER', displayName: 'Jean' },
  };

  it('adopts the reissued pair instead of keeping the revoked one', async () => {
    await fillAndSubmit();
    backend.expectOne(`${BASE}/api/v1/me/password`).flush(newPair);
    await fixture.whenStable();

    expect(store.tokens).toEqual(
      expect.objectContaining({ accessToken: 'access-new', refreshToken: 'refresh-new' }),
    );
    expect(auth.accessToken()).toBe('access-new');
  });

  it('leaves the user signed in', async () => {
    await fillAndSubmit();
    backend.expectOne(`${BASE}/api/v1/me/password`).flush(newPair);
    await fixture.whenStable();

    // The operation that closes every session must not close the one in front of the
    // person who asked for it.
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.user()?.email).toBe('jean@exemple.fr');
  });

  it('says so, since being left signed in otherwise looks like nothing happened', async () => {
    await fillAndSubmit();
    backend.expectOne(`${BASE}/api/v1/me/password`).flush(newPair);
    await fixture.whenStable();

    const banner = fixture.nativeElement.querySelector('.alert--success') as HTMLElement;
    expect(banner.textContent).toContain('déconnect');
    expect(banner.textContent).toContain('reste connecté');
  });

  it('sends the current password and a label for the session it creates', async () => {
    await fillAndSubmit();

    const body = backend.expectOne(`${BASE}/api/v1/me/password`).request.body;
    expect(body).toMatchObject({
      currentPassword: 'correct-horse-battery',
      newPassword: 'a-brand-new-passphrase',
    });
    // Without it, the session this call establishes shows up unnamed on the sessions screen
    // — which is the screen a user is most likely to visit immediately afterwards.
    expect(body.deviceLabel).toBeTruthy();
  });

  it('clears the fields afterwards', async () => {
    await fillAndSubmit();
    backend.expectOne(`${BASE}/api/v1/me/password`).flush(newPair);
    await fixture.whenStable();

    // Three real passwords, on a screen the user has finished with, in a form a browser may
    // offer to restore.
    expect(byLabel('Mot de passe actuel').value).toBe('');
    expect(byLabel('Nouveau mot de passe').value).toBe('');
  });

  it('warns up front that other sessions will end', () => {
    const notice = fixture.nativeElement.querySelector('.alert--info') as HTMLElement;
    expect(notice.textContent).toContain('ferme toutes vos sessions');
  });

  it('keeps the session when the current password is wrong', async () => {
    await fillAndSubmit('not-the-right-one');

    backend
      .expectOne(`${BASE}/api/v1/me/password`)
      .flush(
        { code: ErrorCode.CURRENT_PASSWORD_INCORRECT },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    expect(
      (fixture.nativeElement.querySelector('.alert--error') as HTMLElement).textContent,
    ).toContain('Le mot de passe actuel est incorrect.');
    // Nothing changed server-side, so nothing may change here either.
    expect(store.tokens?.accessToken).toBe('access-old');
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('refuses a confirmation that does not match, without calling the API', async () => {
    await type('Mot de passe actuel', 'correct-horse-battery');
    await type('Nouveau mot de passe', 'a-brand-new-passphrase');
    await type('Confirmer le nouveau mot de passe', 'a-different-passphrase');
    (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    backend.expectNone(`${BASE}/api/v1/me/password`);
    expect(fixture.nativeElement.textContent).toContain('ne correspondent pas');
  });
});
