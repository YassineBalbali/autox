import { Routes } from '@angular/router';

/** An entry in the header's primary navigation. */
export interface NavItem {
  readonly label: string;
  /** Top-level route path, without a leading slash — matched against the route table. */
  readonly path: string;
}

/**
 * The marketplace's primary navigation, declared in one place.
 *
 * <p>Both destinations are the ones a vehicle marketplace is built around, and neither
 * screen exists yet. They are declared anyway because the header is being built now and
 * the shape of it depends on them — a header designed around a wordmark and an account
 * menu is a different header from one with navigation in the middle, and discovering that
 * when search lands means doing it twice.
 */
export const PRIMARY_NAV: readonly NavItem[] = [
  { label: 'Acheter', path: 'annonces' },
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
      .map((route) => route.path)
      .filter((path): path is string => typeof path === 'string' && path !== '' && path !== '**'),
  );
  return items.filter((item) => declared.has(item.path));
}
