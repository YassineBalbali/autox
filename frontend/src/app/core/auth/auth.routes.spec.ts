import { describe, expect, it } from 'vitest';

import { ACCOUNT_ROUTE, SIGN_IN_ROUTE, safeReturnUrl } from './auth.routes';

/**
 * The return-URL check.
 *
 * <p>A security test rather than a formatting one. `?suivant=` is attacker-chosen — it is
 * in a link anybody can send — and a sign-in page that navigates to whatever it finds there
 * is a phishing hop through our own domain: the real form, our real certificate, and then a
 * convincing copy of it holding the password that was just typed.
 */
describe('safeReturnUrl', () => {
  it.each([
    '/mes-annonces',
    '/annonces/0193f2c0-0000-7000-8000-000000000000',
    '/mon-compte/sessions',
    '/recherche?marque=renault&page=2',
  ])('allows the same-origin path %s', (path) => {
    expect(safeReturnUrl(path)).toBe(path);
  });

  it.each([
    // The obvious one.
    'https://autox-connexion.example/',
    'http://evil.example/connexion',
    // Protocol-relative: resolves to another origin while reading like a path, and it is
    // exactly what a naive startsWith('/') check waves through.
    '//evil.example/connexion',
    // Several browsers treat a backslash as a slash when resolving, so this is the same
    // attack wearing a different character.
    '/\\evil.example/connexion',
    // Not a path at all.
    'javascript:alert(1)',
    'mailto:someone@example.com',
  ])('refuses %s and falls back to the account', (hostile) => {
    expect(safeReturnUrl(hostile)).toBe(ACCOUNT_ROUTE);
  });

  it('falls back when there is no parameter at all', () => {
    expect(safeReturnUrl(null)).toBe(ACCOUNT_ROUTE);
    expect(safeReturnUrl(undefined)).toBe(ACCOUNT_ROUTE);
    expect(safeReturnUrl('')).toBe(ACCOUNT_ROUTE);
  });

  it('refuses the sign-in page itself, which would be a loop rather than a return', () => {
    expect(safeReturnUrl(SIGN_IN_ROUTE)).toBe(ACCOUNT_ROUTE);
    expect(safeReturnUrl(`${SIGN_IN_ROUTE}?suivant=%2Fmon-compte`)).toBe(ACCOUNT_ROUTE);
  });
});
