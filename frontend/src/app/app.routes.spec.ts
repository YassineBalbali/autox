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
 * that does not match what the plumbing or the backend expects renders an empty page rather
 * than an error: a redirect to a route that does not exist is not a 404 in a single-page
 * application, it is nothing at all.
 */
describe('routes', () => {
  const find = (path: string): Route => {
    const route = routes.find((candidate) => candidate.path === path);
    expect(route, `no route for "${path}"`).toBeDefined();
    return route!;
  };

  const path = (route: string) => route.replace(/^\//, '');

  it('has a screen behind every path the auth plumbing redirects to', () => {
    // These are the three the interceptor and the guards navigate to by name. A wrong value
    // in `auth.routes.ts` would leave a user staring at an empty shell.
    for (const named of [SIGN_IN_ROUTE, FORBIDDEN_ROUTE, ACCOUNT_ROUTE]) {
      expect(find(path(named)).loadComponent).toBeTypeOf('function');
    }
  });

  it('guards every account screen', () => {
    for (const guarded of [
      'mon-compte',
      'mon-compte/mot-de-passe',
      'mon-compte/sessions',
      'mon-compte/entreprise',
    ]) {
      expect(find(guarded).canActivate, `${guarded} is unguarded`).toHaveLength(1);
    }
  });

  it('guards the dealer screen by role, not merely by having a session', () => {
    // authGuard would let a private seller through to a form the API will refuse every save
    // from. The distinction is the whole point of having two guards.
    const company = find('mon-compte/entreprise').canActivate!;
    expect(company[0]).not.toBe(authGuard);

    expect(find('mon-compte').canActivate![0]).toBe(authGuard);
  });

  it('leaves the public screens public', () => {
    for (const open of [
      'connexion',
      'inscription',
      'mot-de-passe-oublie',
      'reinitialiser-mot-de-passe',
      'acces-refuse',
    ]) {
      expect(find(open).canActivate).toBeUndefined();
    }
  });

  it('keeps the reset path the backend links to', () => {
    // The emailed link is built from `autox.mail.password-reset-url` on the backend, which
    // defaults to http://localhost:4200/reinitialiser-mot-de-passe. The two have to agree
    // exactly: a mismatch produces a link that loads the application and shows nothing,
    // and nothing in either build would notice.
    expect(find('reinitialiser-mot-de-passe')).toBeDefined();
  });

  it('loads every screen lazily', () => {
    // A phone on 4G should not download the dealer forms to read a listing.
    for (const route of routes.filter((candidate) => !candidate.redirectTo)) {
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
    for (const route of routes.filter((candidate) => candidate.loadComponent)) {
      expect(route.title, `${route.path} has no title`).toBeTruthy();
      expect(route.title).toContain('AutoX');
    }
  });
});
