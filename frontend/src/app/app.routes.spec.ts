import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from './app.routes';
import { authGuard } from './core/auth/auth.guard';
import { legacySearchRedirect } from './features/search/legacy-search-redirect';
import { ACCOUNT_ROUTE, FORBIDDEN_ROUTE, SIGN_IN_ROUTE } from './core/auth/auth.routes';

/**
 * The route table.
 *
 * <p>Asserted because two kinds of mistake here are silent. A guard left off an account
 * route does not fail anything — the API still refuses the calls, so the screen simply
 * renders errors to an anonymous visitor instead of sending them to sign in. And a path
 * that does not match what the plumbing or the backend expects renders an empty page
 * rather than an error: a redirect to a route that does not exist is not a 404 in a
 * single-page application, it is nothing at all.
 */
describe('routes', () => {
  const top = (path: string): Route => {
    const route = routes.find((candidate) => candidate.path === path);
    expect(route, `no route for "${path}"`).toBeDefined();
    return route!;
  };

  const account = top('mon-compte');

  /** A child of the account layout, by its path relative to `mon-compte`. */
  const child = (path: string): Route => {
    const route = account.children?.find((candidate) => candidate.path === path);
    expect(route, `no account child route for "${path}"`).toBeDefined();
    return route!;
  };

  const strip = (route: string) => route.replace(/^\//, '');

  it('has a screen behind every path the auth plumbing redirects to', () => {
    // These are the three the interceptor and the guards navigate to by name. A wrong
    // value in `auth.routes.ts` would leave a user staring at an empty shell.
    for (const named of [SIGN_IN_ROUTE, FORBIDDEN_ROUTE]) {
      expect(top(strip(named)).loadComponent).toBeTypeOf('function');
    }
    // The account is a layout with an index child rather than a page of its own.
    expect(strip(ACCOUNT_ROUTE)).toBe('mon-compte');
    expect(account.loadComponent).toBeTypeOf('function');
    expect(child('').loadComponent).toBeTypeOf('function');
  });

  it('guards the whole account section once, on the parent', () => {
    // Declared on the layout rather than repeated on each child: four copies of the same
    // guard is four chances to add a fifth screen and forget it.
    expect(account.canActivate).toEqual([authGuard]);
  });

  it('keeps every account screen inside the guarded layout', () => {
    for (const path of ['', 'mot-de-passe', 'sessions', 'entreprise']) {
      expect(child(path).loadComponent, `${path} is not under the layout`).toBeTypeOf('function');
    }
    // And nothing escaped back to the top level, where it would be unguarded.
    expect(routes.filter((route) => route.path?.startsWith('mon-compte/'))).toEqual([]);
  });

  it('guards the dealer screen by role as well', () => {
    // authGuard on the parent only proves there is a session. A private seller reaching
    // the company form would get a page the API refuses every save from.
    const company = child('entreprise').canActivate;
    expect(company).toHaveLength(1);
    expect(company![0]).not.toBe(authGuard);
  });

  it('leaves the public screens public', () => {
    for (const open of [
      'connexion',
      'inscription',
      'mot-de-passe-oublie',
      'reinitialiser-mot-de-passe',
      'acces-refuse',
    ]) {
      expect(top(open).canActivate).toBeUndefined();
    }
  });

  it('keeps the reset path the backend links to', () => {
    // The emailed link is built from `autox.mail.password-reset-url` on the backend, which
    // defaults to http://localhost:4200/reinitialiser-mot-de-passe. The two have to agree
    // exactly: a mismatch produces a link that loads the application and shows nothing,
    // and nothing in either build would notice.
    expect(top('reinitialiser-mot-de-passe')).toBeDefined();
  });

  it('loads every screen lazily', () => {
    // A phone on 4G should not download the dealer forms to read a listing.
    const all = [...routes, ...(account.children ?? [])];
    for (const route of all.filter((candidate) => !candidate.redirectTo)) {
      expect(route.component, `${route.path} is eagerly imported`).toBeUndefined();
      expect(route.loadComponent).toBeTypeOf('function');
    }
  });

  it('gives an unknown path a page that says so, rather than redirecting', () => {
    // A redirect to the home page showed the visitor something other than what they
    // asked for, with nothing explaining why, and made a mistyped link look as though it
    // worked. Last in the table, or it would swallow every route after it.
    const wildcard = routes.at(-1);
    expect(wildcard?.path).toBe('**');
    expect(wildcard?.redirectTo).toBeUndefined();
    expect(wildcard?.loadComponent).toBeTypeOf('function');
  });

  it('serves a listing publicly, by id', () => {
    const listing = top('annonces/:id');
    expect(listing.loadComponent).toBeTypeOf('function');
    // The shop window: reachable from a search engine or a shared link, with no account.
    expect(listing.canActivate).toBeUndefined();
  });

  it('serves the home page publicly, forwarding only old search links', () => {
    const home = top('');
    expect(home.loadComponent).toBeTypeOf('function');
    expect(home.redirectTo).toBeUndefined();
    // Reachable without an account, and crawlable: its one guard forwards `/?marque=…`
    // to the results and lets everything else through. home-page.spec.ts tests it.
    expect(home.canActivate).toEqual([legacySearchRedirect]);
    // Without this an empty path would also prefix-match every other URL.
    expect(home.pathMatch).toBe('full');
  });

  it('serves the search results at /recherche, publicly', () => {
    const results = top('recherche');
    expect(results.loadComponent).toBeTypeOf('function');
    // The shop window has to be reachable without an account, and crawlable.
    expect(results.canActivate).toBeUndefined();
  });

  it('guards the administration by role, with the dashboard as its landing page', () => {
    const moderation = routes.find((route) => route.path === 'admin/moderation')!;
    // A role guard, not the session guard: being signed in is not enough. (It also turns
    // away a visitor with no session, which is why it stands alone.)
    expect(moderation.canActivate).toHaveLength(1);
    expect(moderation.canActivate![0]).not.toBe(authGuard);
    expect(moderation.title).toContain('AutoX');

    const index = routes.find((route) => route.path === 'admin')!;
    expect(index.redirectTo).toBeUndefined();
    expect(index.pathMatch).toBe('full');
    expect(index.canActivate).toHaveLength(1);
    expect(index.canActivate![0]).not.toBe(authGuard);
    expect(index.title).toContain('Tableau de bord');
    // Nothing else lives under /admin unguarded.
    for (const route of routes.filter((candidate) => candidate.path?.startsWith('admin/'))) {
      expect(route.canActivate, route.path).toHaveLength(1);
    }
  });

  it('puts every selling screen behind a session', () => {
    // Each of them reads or writes the caller's own listings. The API refuses without a
    // token regardless; the guard is what turns that refusal into the sign-in screen.
    for (const path of ['vendre', 'mes-annonces', 'mes-annonces/:id/modifier']) {
      const route = routes.find((candidate) => candidate.path === path);
      expect(route, path).toBeDefined();
      expect(route!.canActivate, path).toEqual([authGuard]);
    }
  });

  it('serves create and edit with one component', async () => {
    // "Same form, loaded with existing values" — literally the same class.
    const load = (path: string) => routes.find((candidate) => candidate.path === path)!.loadComponent!();
    expect(await load('vendre')).toBe(await load('mes-annonces/:id/modifier'));
  });

  it('titles every screen, since the tab is how a user finds it again', () => {
    // The layout itself carries no title — its children do, so the tab names the screen
    // rather than the section.
    // A listing titles itself once it knows which car it is; a static title here would
    // be "Annonce" on every tab.
    // The editor does the same on its edit route: "Modifier « Peugeot 308 »".
    const selfTitled = ['mon-compte', 'annonces/:id', 'mes-annonces/:id/modifier'];
    const screens = [
      ...routes.filter((route) => route.loadComponent && !selfTitled.includes(route.path ?? '')),
      ...(account.children ?? []),
    ];
    for (const route of screens) {
      expect(route.title, `${route.path} has no title`).toBeTruthy();
      expect(route.title).toContain('AutoX');
    }
  });
});
