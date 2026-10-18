import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, catchError, combineLatest, map, of, startWith } from 'rxjs';

import { Make, ResultCount } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { Icon } from '../../shared/ui/icon';
import { SEARCH_PATH } from '../search/legacy-search-redirect';
import { RecentSearches } from '../search/recent-searches';
import { toQueryParams } from '../search/search-query';
import { SearchResolver } from '../search/search-resolver';
import { HomeHero } from './home-hero';
import { BUDGETS, CATEGORIES, Shortcut, TRUST_POINTS } from './home-shortcuts';
import { RecentSearchCards } from './recent-search-cards';
import { ShortcutTile } from './shortcut-tile';

/** `undefined` while the count is on its way, `null` if it never arrives. */
type Count = ResultCount | null | undefined;

/** How many makes the "Marques populaires" rows have room for. */
const POPULAR_LIMIT = 10;

/**
 * The home page: a way in to the search.
 *
 * <p>Everything on it is a search with some filters already set — the search card, the
 * visitor's recent searches, the make, budget and category tiles — and every one of them
 * leads to `/recherche` with those filters in the query string. So the home page holds no
 * search state of its own and invents no filter the results page does not have: each tile
 * is a link that could have been typed by hand.
 *
 * <p>The numbers on the tiles are live counts from the same endpoint the results page
 * uses. They arrive one by one, each independently, and a count that fails leaves its
 * tile without a number rather than without a link.
 */
@Component({
  selector: 'app-home-page',
  imports: [RouterLink, Icon, HomeHero, RecentSearchCards, ShortcutTile],
  templateUrl: './home-page.html',
  styleUrl: './home-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomePage {
  private readonly reference = inject(ReferenceStore);
  private readonly resolver = inject(SearchResolver);
  protected readonly recent = inject(RecentSearches);

  protected readonly searchPath = SEARCH_PATH;
  protected readonly trustPoints = TRUST_POINTS;

  private readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter((make) => !!make.id && !!make.slug)),
      catchError(() => of([] as Make[])),
    ),
    { initialValue: [] as Make[] },
  );

  /**
   * The catalogue's popular makes first, then the others, each group alphabetical, to
   * fill the rows. Popularity is the catalogue's own flag, not a count: it says which makes
   * French buyers look for, which is what a shortcut is for, and it costs no request.
   */
  protected readonly popularMakes = computed(() => {
    const makes = [...this.makes()].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '', 'fr'));
    const popular = makes.filter((make) => make.popular);
    const others = makes.filter((make) => !make.popular);
    return [...popular, ...others].slice(0, POPULAR_LIMIT);
  });

  protected readonly budgets = this.withCounts(BUDGETS);
  protected readonly categories = this.withCounts(CATEGORIES);

  /** Each shortcut with its live count, which fills in when it arrives. */
  private withCounts<T extends Shortcut>(shortcuts: readonly T[]) {
    const counts$: Observable<Count[]> = combineLatest(
      shortcuts.map((shortcut) => this.resolver.count(shortcut.query).pipe(startWith(undefined as Count))),
    );
    const counts = toSignal(counts$, { initialValue: shortcuts.map(() => undefined as Count) });
    return computed(() =>
      shortcuts.map((shortcut, index) => ({
        ...shortcut,
        params: toQueryParams(shortcut.query),
        count: counts()[index],
      })),
    );
  }
}
