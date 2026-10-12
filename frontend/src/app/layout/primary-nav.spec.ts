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

  it('is not fooled by the empty path either', () => {
    const onlyIndex: Routes = [{ path: '', redirectTo: '/mon-compte', pathMatch: 'full' }];

    expect(availableNav(onlyIndex, nav)).toEqual([]);
  });

  it('shows nothing today, because neither screen has been built', () => {
    // A deliberate assertion rather than an oversight: it documents the current state and
    // will fail the day the search screen lands, which is the moment somebody should look
    // at the header again.
    expect(availableNav(routes)).toEqual([]);
    expect(PRIMARY_NAV.map((item) => item.path)).toEqual(['annonces', 'vendre']);
  });
});
