import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, UserRole } from '../core/api';
import { AuthService } from '../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../core/auth/token-store';
import { SiteHeader } from './site-header';

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

const SESSION: StoredTokens = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  accessTokenExpiresAt: Date.now() + 900_000,
};

/**
 * The site header.
 *
 * <p>Two things here are worth holding in place. It renders inside the same `.page`
 * container as the content below it — a header spanning the viewport while the page is
 * capped at 1200px is the detail that makes a desktop layout look like a stretched phone
 * one. And the account menu has to close on an outside click, because a dropdown that
 * only closes via its own button is the most reliably irritating control on a page.
 */
describe('SiteHeader', () => {
  let fixture: ComponentFixture<SiteHeader>;
  let auth: AuthService;

  async function render(signedIn: boolean): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // Signing out navigates to the sign-in page, so the test router needs that route:
        // without it the navigation rejects after the test has finished, which Vitest
        // reports as an unhandled error and CI treats as a failure.
        provideRouter([{ path: 'connexion', children: [] }]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: new InMemoryTokenStore(signedIn ? SESSION : null) },
      ],
    });

    auth = TestBed.inject(AuthService);
    auth.restore();

    fixture = TestBed.createComponent(SiteHeader);
    await fixture.whenStable();
  }

  const text = () => fixture.nativeElement.textContent as string;
  const query = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;

  it('puts its contents in the shared container', async () => {
    await render(false);

    // `.page` is the one max-width container, used by main and the footer too. Without it
    // the wordmark sits against the window edge while the page title below is indented.
    expect(query('.page')).not.toBeNull();
  });

  it('always shows the wordmark, linking home', async () => {
    await render(false);

    const brand = query('.header__brand') as HTMLAnchorElement;
    // The mark is artwork now, not text, so its name has to come from somewhere a screen
    // reader can find it: the SVG's own label, and the link's.
    expect(brand.querySelector('svg[aria-label="AutoX"]')).not.toBeNull();
    expect(brand.getAttribute('aria-label')).toContain('AutoX');
    expect(brand.getAttribute('href')).toBe('/');
  });

  describe('signed out', () => {
    it('offers sign-in and registration', async () => {
      await render(false);

      expect(text()).toContain('Se connecter');
      expect(text()).toContain('Créer un compte');
      expect(query('.account-menu')).toBeNull();
    });
  });

  describe('signed in', () => {
    it('shows the account menu instead of the sign-in links', async () => {
      await render(true);
      auth.setUser({ id: 'u-1', email: 'jean@exemple.fr', displayName: 'Garage Bellevue' });
      await fixture.whenStable();

      expect(query('.account-menu__button')?.textContent).toContain('Garage Bellevue');
      expect(text()).not.toContain('Créer un compte');
    });

    it('falls back to the email before the profile has loaded', async () => {
      // On a reload the session is restored from storage but the display name is not — it
      // is deliberately not persisted. A blank button would be the alternative.
      await render(true);
      auth.setUser({ id: 'u-1', email: 'jean@exemple.fr' });
      await fixture.whenStable();

      expect(query('.account-menu__button')?.textContent).toContain('jean@exemple.fr');
    });

    it('never renders a blank button, even with no user loaded at all', async () => {
      await render(true);
      expect(query('.account-menu__button')?.textContent?.trim()).toBeTruthy();
    });

    it('opens and closes the menu, and says which it is', async () => {
      await render(true);
      const button = query('.account-menu__button') as HTMLButtonElement;

      expect(button.getAttribute('aria-expanded')).toBe('false');
      expect(query('.account-menu__panel')).toBeNull();

      button.click();
      await fixture.whenStable();

      expect(button.getAttribute('aria-expanded')).toBe('true');
      expect(query('.account-menu__panel')).not.toBeNull();

      button.click();
      await fixture.whenStable();
      expect(query('.account-menu__panel')).toBeNull();
    });

    it('closes on a click anywhere else', async () => {
      await render(true);
      (query('.account-menu__button') as HTMLButtonElement).click();
      await fixture.whenStable();
      expect(query('.account-menu__panel')).not.toBeNull();

      document.body.click();
      await fixture.whenStable();

      expect(query('.account-menu__panel')).toBeNull();
    });

    it('closes on Escape', async () => {
      await render(true);
      (query('.account-menu__button') as HTMLButtonElement).click();
      await fixture.whenStable();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await fixture.whenStable();

      expect(query('.account-menu__panel')).toBeNull();
    });

    it('leads to the account and offers a way out', async () => {
      await render(true);
      (query('.account-menu__button') as HTMLButtonElement).click();
      await fixture.whenStable();

      const panel = query('.account-menu__panel') as HTMLElement;
      const destinations = Array.from(panel.querySelectorAll('a')).map((a) =>
        a.getAttribute('href'),
      );
      expect(destinations).toContain('/mon-compte');
      expect(panel.textContent).toContain('Se déconnecter');
    });

    it('signs out and clears the session', async () => {
      await render(true);
      (query('.account-menu__button') as HTMLButtonElement).click();
      await fixture.whenStable();

      const signOut = Array.from(
        (query('.account-menu__panel') as HTMLElement).querySelectorAll('button'),
      ).find((button) => button.textContent?.includes('Se déconnecter')) as HTMLButtonElement;
      signOut.click();
      await fixture.whenStable();

      expect(auth.isAuthenticated()).toBe(false);
      expect(query('.account-menu__panel')).toBeNull();
    });
  });

  it('offers only the destinations that exist', async () => {
    // Both entries have a route. "Vendre" is offered to a visitor too: the guard on it
    // sends them to sign in and brings them back, which is the right first step.
    await render(false);

    const links = Array.from(
      (query('.header__nav') as HTMLElement).querySelectorAll('a'),
    ).map((link) => [link.textContent?.trim(), link.getAttribute('href')]);
    expect(links).toEqual([
      ['Acheter', '/'],
      ['Vendre', '/vendre'],
    ]);
  });

  it('does not render an account menu for a role it has not loaded', async () => {
    // The header shows no role-specific entry at all, which is the only safe default:
    // the role arrives from GET /me, and guessing it would mean showing dealer links to
    // whoever reloads the page.
    await render(true);
    expect(text()).not.toContain(UserRole.PROFESSIONNEL);
  });
});
