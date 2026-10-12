import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BASE_PATH, UserProfileResponse, UserRole } from '../../core/api';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { AccountLayout, activeKey } from './account-layout';

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

const PROFILE: UserProfileResponse = {
  id: 'u-1',
  email: 'jean@exemple.fr',
  role: UserRole.PARTICULIER,
  displayName: 'Jean D.',
};

/**
 * The account section's sidebar.
 *
 * <p>The behaviour that earns the layout its existence is the dealer entry. The sidebar
 * renders before any page inside it, so it needs the account's role — and arriving
 * directly at `/mon-compte/sessions`, which is what the header's menu does, means nothing
 * else has asked for it. Before the shared load, that case silently hid a dealer's own
 * company screen from them.
 */
describe('AccountLayout', () => {
  let fixture: ComponentFixture<AccountLayout>;
  let backend: HttpTestingController;

  async function render(url: string, profile: UserProfileResponse = PROFILE): Promise<void> {
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

    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue(url);

    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AccountLayout);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/me`).flush(profile);
    await fixture.whenStable();
  }

  const labels = () =>
    Array.from(fixture.nativeElement.querySelectorAll('.account-nav__link')).map((link) =>
      (link as HTMLElement).textContent?.trim(),
    );

  const active = () =>
    (
      fixture.nativeElement.querySelector('.account-nav__link--active') as HTMLElement | null
    )?.textContent?.trim();

  it('lists the account in one place', async () => {
    await render('/mon-compte');

    expect(labels()).toEqual(['Identité', 'Mes informations', 'Mot de passe', 'Sessions']);
  });

  it('loads the profile once for the whole section', async () => {
    await render('/mon-compte/sessions');

    // The store is scoped to the layout and `ensureLoaded` is idempotent, so a page
    // rendered inside it attaches to this result rather than asking again.
    backend.expectNone(`${BASE}/api/v1/me`);
  });

  describe('the dealer entry', () => {
    it('appears for a professional account', async () => {
      await render('/mon-compte', { ...PROFILE, role: UserRole.PROFESSIONNEL });

      expect(labels()).toContain('Entreprise');
    });

    it('appears for an administrator, who has to be able to support one', async () => {
      await render('/mon-compte', { ...PROFILE, role: UserRole.ADMIN });

      expect(labels()).toContain('Entreprise');
    });

    it('is absent for a private seller', async () => {
      await render('/mon-compte');

      expect(labels()).not.toContain('Entreprise');
    });

    it('appears on a screen that never reads the profile itself', async () => {
      // The case the shared load exists for. The sessions page asks for sessions, not for
      // the profile — so without the layout loading it, a dealer landing here from the
      // header menu would find their own company screen missing from the sidebar.
      await render('/mon-compte/sessions', { ...PROFILE, role: UserRole.PROFESSIONNEL });

      expect(labels()).toContain('Entreprise');
    });
  });

  describe('marking the current entry', () => {
    it('marks the route being viewed', async () => {
      await render('/mon-compte/sessions');
      expect(active()).toBe('Sessions');
    });

    it('marks Identité at the top of the profile page', async () => {
      await render('/mon-compte');
      expect(active()).toBe('Identité');
    });

    it('marks exactly one entry, never two', async () => {
      // Identité and Mes informations are both on `/mon-compte`, so a rule based on the
      // path alone would light up both of them.
      await render('/mon-compte');
      expect(fixture.nativeElement.querySelectorAll('.account-nav__link--active')).toHaveLength(1);
    });

    it('tells a screen reader which one, not only a sighted user', async () => {
      await render('/mon-compte/mot-de-passe');

      const current = fixture.nativeElement.querySelector('[aria-current="page"]') as HTMLElement;
      expect(current.textContent?.trim()).toBe('Mot de passe');
    });
  });

  /** The rule on its own, where every case is one line. */
  describe('activeKey', () => {
    it.each([
      ['/mon-compte', '/mon-compte#identite'],
      ['/mon-compte#identite', '/mon-compte#identite'],
      ['/mon-compte#mes-informations', '/mon-compte#mes-informations'],
      // An unknown fragment is still the profile page, and the top of it is Identité.
      ['/mon-compte#autre-chose', '/mon-compte#identite'],
      ['/mon-compte/sessions', '/mon-compte/sessions'],
      ['/mon-compte/entreprise', '/mon-compte/entreprise'],
      // A query string belongs to neither the path nor the fragment.
      ['/mon-compte?x=1', '/mon-compte#identite'],
      ['/mon-compte/sessions?x=1', '/mon-compte/sessions'],
    ])('%s resolves to %s', (url, expected) => {
      expect(activeKey(url)).toBe(expected);
    });
  });
});
