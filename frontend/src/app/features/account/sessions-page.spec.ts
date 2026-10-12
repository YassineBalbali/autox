import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, SessionResponse } from '../../core/api';
import { deviceLabel } from '../../core/auth/device-label';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { SessionsPage } from './sessions-page';

const BASE = 'http://api.test';

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
 * The sessions list.
 *
 * <p>The tests that matter are about the "cet appareil" badge. The API cannot say which row
 * belongs to the caller — an access token carries no reference to the refresh family it was
 * issued with — so the client matches on the device label it sends at sign-in. That is a
 * guess, and the one case it gets wrong is two sessions from the same browser. Marking the
 * wrong row is how a stolen session survives this screen, so when the label is ambiguous
 * nothing is marked at all. The test for that is `marks nothing when two sessions share a
 * label`.
 */
describe('SessionsPage', () => {
  let fixture: ComponentFixture<SessionsPage>;
  let backend: HttpTestingController;

  /** The label this browser produces — the same value the component computes. */
  const thisBrowser = deviceLabel();

  const session = (over: Partial<SessionResponse>): SessionResponse => ({
    id: '0193f2c0-0000-7000-8000-000000000001',
    deviceLabel: 'Safari sur iPhone',
    userAgent: 'Mozilla/5.0 (iPhone)',
    ipAddress: '81.250.0.1',
    createdAt: '2026-03-01T09:15:00Z',
    lastUsedAt: '2026-03-10T18:02:00Z',
    expiresAt: '2026-05-30T09:15:00Z',
    ...over,
  });

  async function render(sessions: SessionResponse[]): Promise<void> {
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

    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SessionsPage);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/me/sessions`).flush(sessions);
    await fixture.whenStable();
  }

  const rows = () =>
    Array.from(fixture.nativeElement.querySelectorAll('.session')) as HTMLElement[];

  const badges = () => fixture.nativeElement.querySelectorAll('.session__badge');

  it('lists every live session with what the user needs to recognise it', async () => {
    await render([
      session({ id: 'a', deviceLabel: 'Safari sur iPhone' }),
      session({ id: 'b', deviceLabel: 'Chrome sur Windows' }),
    ]);

    expect(rows()).toHaveLength(2);
    expect(rows()[0].textContent).toContain('Safari sur iPhone');
    expect(rows()[0].textContent).toContain('81.250.0.1');
    // Rendered in the viewer's own zone, from a UTC instant. "Connexion le 1 mars" has to
    // mean the date where the person was, or they cannot recognise their own sign-in.
    expect(rows()[0].textContent).toContain('mars 2026');
  });

  it('names a session the server has no label for', async () => {
    // The API returns null when the client said nothing at sign-in. A blank row reads as a
    // rendering fault rather than as an unnamed device.
    await render([session({ deviceLabel: undefined })]);

    expect(rows()[0].textContent).toContain('Appareil non identifié');
  });

  describe('marking this device', () => {
    it('marks the one session whose label matches this browser', async () => {
      await render([
        session({ id: 'a', deviceLabel: thisBrowser }),
        session({ id: 'b', deviceLabel: 'Safari sur iPhone' }),
      ]);

      expect(badges()).toHaveLength(1);
      expect(rows()[0].textContent).toContain('Cet appareil');
    });

    it('marks nothing when two sessions share a label', async () => {
      // Two sign-ins from the same browser are indistinguishable. Marking the first would
      // be a coin flip presented as a fact — and the coin landing wrong is a user leaving a
      // stolen session open because the screen told them it was their own.
      await render([
        session({ id: 'a', deviceLabel: thisBrowser }),
        session({ id: 'b', deviceLabel: thisBrowser }),
      ]);

      expect(badges()).toHaveLength(0);
    });

    it('explains, so the absence of the badge is not read as a fault', async () => {
      await render([session({ deviceLabel: thisBrowser }), session({ deviceLabel: thisBrowser })]);

      expect(fixture.nativeElement.textContent).toContain('indistinguables');
    });

    it('marks nothing when no label matches', async () => {
      await render([session({ deviceLabel: 'Firefox sur Linux' })]);
      expect(badges()).toHaveLength(0);
    });
  });

  describe('ending a session', () => {
    it('calls the API with the session id and drops the row', async () => {
      await render([session({ id: 'a' }), session({ id: 'b' })]);

      (rows()[0].querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable();

      backend.expectOne(`${BASE}/api/v1/me/sessions/a`).flush(null, { status: 204, statusText: '' });
      await fixture.whenStable();

      expect(rows()).toHaveLength(1);
      // Not refetched. Re-reading the list would send a request on the very session that
      // may have just been ended, turning a success into a sign-out.
      backend.expectNone(`${BASE}/api/v1/me/sessions`);
    });

    it('treats an already-gone session as done, with nothing to report', async () => {
      // A 404 is the ordinary case: the session expired, or was ended from another device
      // while this page sat open. The user asked for it to be gone and it is gone.
      await render([session({ id: 'a' })]);

      (rows()[0].querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable();

      backend
        .expectOne(`${BASE}/api/v1/me/sessions/a`)
        .flush({ code: ErrorCode.NOT_FOUND }, { status: 404, statusText: 'Not Found' });
      await fixture.whenStable();

      expect(rows()).toHaveLength(0);
      expect(fixture.nativeElement.querySelector('.alert--error')).toBeNull();
    });

    it('keeps the row when the failure means the session may still be live', async () => {
      await render([session({ id: 'a' })]);

      (rows()[0].querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable();

      backend
        .expectOne(`${BASE}/api/v1/me/sessions/a`)
        .flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'Server Error' });
      await fixture.whenStable();

      // Removing it would tell the user a device had been cut off when it had not — the one
      // lie this screen must not tell.
      expect(rows()).toHaveLength(1);
      expect(fixture.nativeElement.querySelector('.alert--error')).not.toBeNull();
    });

    it('disables only the button of the session being ended', async () => {
      await render([session({ id: 'a' }), session({ id: 'b' })]);

      (rows()[0].querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect((rows()[0].querySelector('button') as HTMLButtonElement).disabled).toBe(true);
      expect((rows()[1].querySelector('button') as HTMLButtonElement).disabled).toBe(false);
    });

    it('warns on the button when ending the session in use', async () => {
      await render([session({ deviceLabel: thisBrowser })]);

      expect((rows()[0].querySelector('button') as HTMLButtonElement).textContent).toContain(
        'déconnecté',
      );
    });
  });

  it('offers a retry when the list cannot be loaded', async () => {
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
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SessionsPage);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/me/sessions`).error(new ProgressEvent('offline'));
    await fixture.whenStable();

    expect(
      (fixture.nativeElement.querySelector('.alert--error') as HTMLElement).textContent,
    ).toContain('Vérifiez votre connexion');

    // Reloading the whole page to recover from a dropped connection is a heavier ask than
    // one button.
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    backend.expectOne(`${BASE}/api/v1/me/sessions`).flush([session({})]);
    await fixture.whenStable();

    expect(rows()).toHaveLength(1);
  });
});
