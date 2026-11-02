import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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

  it('carries the phone’s menu, fed the same navigation as the desktop bar', async () => {
    await render(false);

    const drawer = query('app-nav-drawer') as HTMLElement;
    expect(drawer).not.toBeNull();
    // First in the bar, before the wordmark, where a thumb expects it.
    expect(query('.header__inner')?.firstElementChild?.tagName).toBe('APP-NAV-DRAWER');
    const drawerLinks = Array.from(drawer.querySelectorAll('nav a')).map((a) => a.getAttribute('href'));
    const barLinks = Array.from((query('.header__nav') as HTMLElement).querySelectorAll('a')).map((a) =>
      a.getAttribute('href'),
    );
    expect(drawerLinks).toEqual(barLinks);
  });

  describe('signed out', () => {
    it('offers sign-in and registration', async () => {
      await render(false);

      expect(text()).toContain('Se connecter');
      expect(text()).toContain('Créer un compte');
      expect(query('.account-menu')).toBeNull();
      expect(query('.header__account-link')).toBeNull();
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

    it('gives a phone the account as an icon, named in words', async () => {
      await render(true);
      auth.setUser({ id: 'u-1', email: 'jean@exemple.fr', displayName: 'Garage Bellevue' });
      await fixture.whenStable();

      const link = query('.header__account-link') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/mon-compte');
      expect(link.getAttribute('aria-label')).toBe('Mon compte — Garage Bellevue');
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

  describe('messages', () => {
    const UNREAD = `${BASE}/api/v1/conversations/unread`;

    it('links a signed-in user to their messages, with how many conversations wait', async () => {
      await render(true);
      TestBed.inject(HttpTestingController).expectOne(UNREAD).flush({ conversations: 3 });
      await fixture.whenStable();

      const link = query('app-messages-link a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/messages');
      expect(link.querySelector('.count-badge')?.textContent?.trim()).toBe('3');
      // The number is said in words too: "Messages 3" alone tells a screen reader nothing.
      expect(link.textContent).toContain(', 3 conversations non lues');
    });

    it('shows no number when nothing waits, and no link for a visitor', async () => {
      await render(true);
      TestBed.inject(HttpTestingController).expectOne(UNREAD).flush({ conversations: 0 });
      await fixture.whenStable();
      expect(query('app-messages-link .count-badge')).toBeNull();

      await render(false);
      expect(query('app-messages-link')).toBeNull();
    });

    it('caps a large number, which the badge would not hold', async () => {
      await render(true);
      TestBed.inject(HttpTestingController).expectOne(UNREAD).flush({ conversations: 140 });
      await fixture.whenStable();

      expect(query('app-messages-link .count-badge')?.textContent?.trim()).toBe('99+');
    });
  });

  it('offers only the destinations that exist', async () => {
    // Every entry has a route. "Vendre" is offered to a visitor too: the guard on it
    // sends them to sign in and brings them back, which is the right first step.
    await render(false);

    const links = Array.from(
      (query('.header__nav') as HTMLElement).querySelectorAll('a'),
    ).map((link) => [link.textContent?.trim(), link.getAttribute('href')]);
    expect(links).toEqual([
      ['Acheter', '/recherche'],
      ['Vendre', '/vendre'],
      ['La Cote', '/la-cote'],
    ]);
  });

  describe('the administration entry', () => {
    const ME = `${BASE}/api/v1/me`;
    const openMenu = async () => {
      (query('.account-menu__button') as HTMLButtonElement).click();
      await fixture.whenStable();
    };
    const entries = () =>
      Array.from(fixture.nativeElement.querySelectorAll('.account-menu__item')).map((item) =>
        (item as HTMLElement).textContent?.trim(),
      );

    it('is offered to an administrator, first in the menu', async () => {
      await render(true);
      auth.setUser({ id: 'u-1', email: 'admin@exemple.fr', role: UserRole.ADMIN });
      await fixture.whenStable();
      await openMenu();

      expect(entries()[0]).toBe('Administration');
      const link = Array.from(fixture.nativeElement.querySelectorAll('.account-menu__item'))[0] as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/admin');
    });

    it.each([UserRole.PARTICULIER, UserRole.PROFESSIONNEL])('is not offered to a %s', async (role) => {
      await render(true);
      auth.setUser({ id: 'u-1', email: 'vendeur@exemple.fr', role });
      await fixture.whenStable();
      await openMenu();

      expect(entries()).not.toContain('Administration');
    });

    it('is absent while the role is unknown, never guessed', async () => {
      // A session restored from storage: tokens, and nothing else, until GET /me answers.
      await render(true);
      await openMenu();

      expect(entries()).not.toContain('Administration');
    });

    it('appears once the profile the header asked for says ADMIN', async () => {
      await render(true);
      const backend = TestBed.inject(HttpTestingController);

      backend.expectOne(ME).flush({ id: 'u-1', email: 'admin@exemple.fr', displayName: 'Camille', role: UserRole.ADMIN });
      await fixture.whenStable();
      await openMenu();

      expect(entries()).toContain('Administration');
      // And the button now says who is signed in.
      expect(query('.account-menu__button')?.textContent).toContain('Camille');
    });

    it('asks for the profile once, and not at all when somebody already has', async () => {
      await render(true);
      const backend = TestBed.inject(HttpTestingController);
      expect(backend.match(ME)).toHaveLength(1);

      auth.setUser({ id: 'u-1', email: 'jean@exemple.fr' });
      await fixture.whenStable();
      auth.setUser(null);
      await fixture.whenStable();

      expect(backend.match(ME)).toHaveLength(0);
    });

    it('asks nothing of a visitor who is not signed in', async () => {
      await render(false);

      TestBed.inject(HttpTestingController).expectNone(ME);
    });

    it('keeps its generic label when the profile cannot be read', async () => {
      await render(true);
      TestBed.inject(HttpTestingController).expectOne(ME).flush(null, { status: 500, statusText: 'Error' });
      await fixture.whenStable();

      expect(query('.account-menu__button')?.textContent?.trim()).toBeTruthy();
    });
  });

  it('does not render an account menu for a role it has not loaded', async () => {
    // The header shows no role-specific entry at all, which is the only safe default:
    // the role arrives from GET /me, and guessing it would mean showing dealer links to
    // whoever reloads the page.
    await render(true);
    expect(text()).not.toContain(UserRole.PROFESSIONNEL);
  });
});
