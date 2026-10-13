import { Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from '../app.routes';
import { PRIMARY_NAV, availableNav } from './primary-nav';

/**
 * Which primary navigation entries are shown.
 *
 * <p>The rule exists to stop the header offering a destination that does not exist. The
 * failure it prevents is quiet rather than loud: the wildcard route sends an unmatched
 * path to the account, so a dead "Acheter" link would not 404 — it would take the visitor
 * to their own profile, or to the sign-in page if they had none.
 */
describe('availableNav', () => {
  const nav = [
    { label: 'Acheter', path: 'annonces' },
    { label: 'Vendre', path: 'vendre' },
  ];

  it('shows an entry once its route exists', () => {
    const withSearch: Routes = [{ path: 'annonces', children: [] }];

    expect(availableNav(withSearch, nav).map((item) => item.label)).toEqual(['Acheter']);
  });

  it('hides an entry whose route has not been built', () => {
    expect(availableNav([], nav)).toEqual([]);
  });

  it('is not fooled by the wildcard, which matches everything', () => {
    // `**` would otherwise make every declared entry look reachable — and every one of
    // them would redirect to the account.
    const onlyWildcard: Routes = [{ path: '**', redirectTo: '/mon-compte' }];

    expect(availableNav(onlyWildcard, nav)).toEqual([]);
  });

  it('does not count a redirect as a destination', () => {
    // An index route that only redirects renders nothing of its own. Treating it as the
    // home page would light up "Acheter" on an application with no search screen.
    const home = [{ label: 'Acheter', path: '' }];
    const onlyRedirect: Routes = [{ path: '', redirectTo: '/mon-compte', pathMatch: 'full' }];

    expect(availableNav(onlyRedirect, home)).toEqual([]);
  });

  it('shows the home entry once the home page renders something', () => {
    const home = [{ label: 'Acheter', path: '' }];
    const withHome: Routes = [{ path: '', pathMatch: 'full', loadComponent: async () => class {} }];

    expect(availableNav(withHome, home).map((item) => item.label)).toEqual(['Acheter']);
  });

  it('shows buying and selling, now that both have a screen', () => {
    // The real route table. "Vendre" was declared long before its route existed and was
    // left out until it did; this is the test that used to assert its absence.
    expect(availableNav(routes).map((item) => item.label)).toEqual(['Acheter', 'Vendre']);
    expect(PRIMARY_NAV.map((item) => item.path)).toEqual(['', 'vendre']);
  });
});
