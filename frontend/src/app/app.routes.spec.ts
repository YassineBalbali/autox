import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from './app.routes';
import { authGuard } from './core/auth/auth.guard';
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

  it('sends an unknown path somewhere real rather than rendering an empty shell', () => {
    const wildcard = routes.at(-1);
    expect(wildcard?.path).toBe('**');
    expect(wildcard?.redirectTo).toBe(ACCOUNT_ROUTE);
  });

  it('titles every screen, since the tab is how a user finds it again', () => {
    // The layout itself carries no title — its children do, so the tab names the screen
    // rather than the section.
    const screens = [
      ...routes.filter((route) => route.loadComponent && route.path !== 'mon-compte'),
      ...(account.children ?? []),
    ];
    for (const route of screens) {
      expect(route.title, `${route.path} has no title`).toBeTruthy();
      expect(route.title).toContain('AutoX');
    }
  });
});
