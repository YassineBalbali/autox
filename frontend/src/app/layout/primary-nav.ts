import { Routes } from '@angular/router';

/** An entry in the header's primary navigation. */
export interface NavItem {
  readonly label: string;
  /**
   * Top-level route path, without a leading slash — matched against the route table.
   * The empty string is the home page.
   */
  readonly path: string;
}

/**
 * The marketplace's primary navigation, declared in one place.
 *
 * <p>The two destinations a vehicle marketplace is built around. Buying is the search,
 * which is the home page; selling has no screen yet, and is declared anyway so that it
 * appears the day its route does, with no change here.
 */
export const PRIMARY_NAV: readonly NavItem[] = [
  { label: 'Acheter', path: '' },
  { label: 'Vendre', path: 'vendre' },
];

/**
 * The entries that currently lead somewhere.
 *
 * <p>Checked against the route table rather than against a hand-maintained `available`
 * flag, so the day the search screen is added its link starts working with no second
 * change to remember. The alternative — rendering a link to a path with no route — is
 * worse than it sounds here: the wildcard sends an unmatched path to the account, so a
 * "Acheter" link would quietly take the user to their own profile.
 */
export function availableNav(routes: Routes, items = PRIMARY_NAV): readonly NavItem[] {
  const declared = new Set(
    routes
      // A route that renders something. A redirect is not a destination, and the wildcard
      // matches every path there is — counting either would make every entry look live.
      .filter((route) => route.path !== '**' && !route.redirectTo)
      .filter((route) => route.loadComponent || route.component || route.children)
      .map((route) => route.path)
      .filter((path): path is string => typeof path === 'string'),
  );
  return items.filter((item) => declared.has(item.path));
}
