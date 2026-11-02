import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, UserRole } from '../core/api';
import { AuthService } from '../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE, TokenStore } from '../core/auth/token-store';
import { stubDialogs } from '../core/ui/dom-testing';
import { NavDrawer } from './nav-drawer';

const BASE = 'http://api.test';
const UNREAD = `${BASE}/api/v1/conversations/unread`;

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

const ITEMS = [
  { label: 'Acheter', path: 'recherche' },
  { label: 'Vendre', path: 'vendre' },
  { label: 'La Cote', path: 'la-cote' },
];

/**
 * The phone's menu drawer.
 *
 * <p>jsdom knows the dialog element but not its modal methods: see {@link stubDialogs}.
 */
describe('NavDrawer', () => {
  stubDialogs();

  let fixture: ComponentFixture<NavDrawer>;
  let auth: AuthService;

  async function render(signedIn: boolean): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'connexion', children: [] },
          { path: 'recherche', children: [] },
        ]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: new InMemoryTokenStore(signedIn ? SESSION : null) },
      ],
    });
    auth = TestBed.inject(AuthService);
    auth.restore();

    fixture = TestBed.createComponent(NavDrawer);
    fixture.componentRef.setInput('items', ITEMS);
    await fixture.whenStable();
  }

  const query = <T extends HTMLElement = HTMLElement>(selector: string) =>
    fixture.nativeElement.querySelector(selector) as T | null;
  const dialog = () => query<HTMLDialogElement>('dialog')!;
  const burger = () => query<HTMLButtonElement>('.burger')!;

  async function open(): Promise<void> {
    burger().click();
    await fixture.whenStable();
  }

  const links = () =>
    Array.from(dialog().querySelectorAll('a.drawer__link')).map((link) => [
      link.textContent?.replace(/\s+/g, ' ').trim(),
      link.getAttribute('href'),
    ]);

  it('is closed until the burger is pressed, and says so', async () => {
    await render(false);

    expect(dialog().open).toBe(false);
    expect(burger().getAttribute('aria-expanded')).toBe('false');
    expect(burger().getAttribute('aria-haspopup')).toBe('dialog');
    // An icon alone: the name is in words for a screen reader.
    expect(burger().textContent?.trim()).toBe('Menu');

    await open();

    expect(dialog().open).toBe(true);
    expect(burger().getAttribute('aria-expanded')).toBe('true');
  });

  it('opens as a modal, so the page behind is inert and focus stays inside', async () => {
    await render(false);
    let modal = false;
    const showModal = HTMLDialogElement.prototype.showModal;
    dialog().showModal = function (this: HTMLDialogElement) {
      modal = true;
      showModal.call(this);
    };

    await open();

    expect(modal).toBe(true);
  });

  it('holds the primary navigation, in the header’s order', async () => {
    await render(false);
    await open();

    expect(links().slice(0, 3)).toEqual([
      ['Acheter', '/recherche'],
      ['Vendre', '/vendre'],
      ['La Cote', '/la-cote'],
    ]);
    expect(dialog().querySelector('nav')?.getAttribute('aria-label')).toBe('Navigation principale');
  });

  it('offers a visitor registration and sign-in, and no account entries', async () => {
    await render(false);
    await open();

    const ctas = Array.from(dialog().querySelectorAll('.drawer__cta')).map((a) => [
      a.textContent?.trim(),
      a.getAttribute('href'),
    ]);
    expect(ctas).toEqual([
      ['Créer un compte', '/inscription'],
      ['Se connecter', '/connexion'],
    ]);
    expect(dialog().textContent).not.toContain('Se déconnecter');
  });

  it('carries everything the desktop account menu does, for a signed-in user', async () => {
    await render(true);
    auth.setUser({ id: 'u-1', email: 'jean@exemple.fr', displayName: 'Garage Bellevue' });
    await fixture.whenStable();
    await open();

    expect(query('.drawer__account-name')?.textContent).toBe('Garage Bellevue');
    expect(links().slice(3)).toEqual([
      ['Messages', '/messages'],
      ['Mes annonces', '/mes-annonces'],
      ['Mon compte', '/mon-compte'],
      ['Appareils connectés', '/mon-compte/sessions'],
    ]);
    expect(dialog().textContent).toContain('Se déconnecter');
    expect(dialog().textContent).not.toContain('Créer un compte');
  });

  it('offers administration only once the role is known to be ADMIN', async () => {
    await render(true);
    await open();
    expect(dialog().textContent).not.toContain('Administration');

    auth.setUser({ id: 'u-1', email: 'admin@exemple.fr', role: UserRole.ADMIN });
    await fixture.whenStable();

    expect(links()).toContainEqual(['Administration', '/admin']);
  });

  it('shows how many conversations wait, in figures and in words', async () => {
    await render(true);
    TestBed.inject(HttpTestingController).expectOne(UNREAD).flush({ conversations: 2 });
    await fixture.whenStable();
    await open();

    const messages = Array.from(dialog().querySelectorAll('a')).find(
      (a) => a.getAttribute('href') === '/messages',
    )!;
    expect(messages.querySelector('.count-badge')?.textContent?.trim()).toBe('2');
    expect(messages.textContent).toContain(', 2 conversations non lues');
  });

  it('closes when one of its links is followed', async () => {
    await render(false);
    await open();

    (dialog().querySelector('a.drawer__link') as HTMLAnchorElement).click();
    await fixture.whenStable();

    expect(dialog().open).toBe(false);
  });

  it('closes on any navigation, the back button included', async () => {
    await render(false);
    await open();

    await TestBed.inject(Router).navigateByUrl('/recherche');
    await fixture.whenStable();

    expect(dialog().open).toBe(false);
    expect(burger().getAttribute('aria-expanded')).toBe('false');
  });

  it('closes from its own button, and from a tap on the backdrop but not on the panel', async () => {
    await render(false);
    await open();
    (query('.drawer__panel') as HTMLElement).click();
    await fixture.whenStable();
    expect(dialog().open).toBe(true);

    dialog().click();
    await fixture.whenStable();
    expect(dialog().open).toBe(false);

    await open();
    (query('.drawer__close') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(dialog().open).toBe(false);
  });

  it('keeps its state honest when the browser closes it, as Escape does', async () => {
    await render(false);
    await open();

    dialog().close();
    await fixture.whenStable();

    expect(burger().getAttribute('aria-expanded')).toBe('false');
    // And it opens again: the state did not stay "open" behind a closed dialog.
    await open();
    expect(dialog().open).toBe(true);
  });

  it('signs out, closed', async () => {
    await render(true);
    await open();

    (query('.drawer__link--danger') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(auth.isAuthenticated()).toBe(false);
    expect(dialog().open).toBe(false);
  });
});
