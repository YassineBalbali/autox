import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { distinctUntilChanged, map, switchMap } from 'rxjs';

import { PublicListingSummary, ResultCount, SearchSort } from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { formatIntegerFr } from '../../shared/format/number';
import { ListingCard } from '../../shared/listing/listing-card';
import { ListingRow } from '../../shared/listing/listing-row';
import { Alert } from '../../shared/ui/alert';
import { ActiveFilters } from './active-filters';
import { FUEL_LABELS, GEARBOX_LABELS, SORT_OPTIONS, labelOf } from '../../shared/listing/labels.fr';
import { SearchBar } from './search-bar';
import { SearchFilters } from './search-filters';
import {
  SearchQuery,
  activeFilterCount,
  parseQuery,
  toQueryParams,
  withoutFilters,
} from './search-query';
import { RecentSearches } from './recent-searches';
import { SearchResolver } from './search-resolver';
import { SearchMemory } from './search-memory';
import { SearchResultsStore } from './search-results.store';
import { ResultsView, ViewPreference } from './view-preference';
import { ViewToggle } from './view-toggle';

/**
 * The search screen, and the home page.
 *
 * <p>The flow is one direction, round a loop:
 *
 * <pre>
 *   URL  →  SearchQuery  →  API criteria  →  results
 *    ↑                                          |
 *    └──── navigate ←── a filter or the sort ───┘
 * </pre>
 *
 * <p>Nothing on the page changes the results directly. A filter change navigates; the new
 * URL is parsed; the parsed query drives the search. Because the URL is the only state,
 * a link reproduces the search exactly, the back button steps back through the filters,
 * and the sidebar cannot show one thing while the list shows another.
 *
 * <p>Three controls feed that loop, each suited to a different moment. The search bar
 * composes a search and applies it on submit; the sidebar refines the list and applies
 * each change at once; the chips between them show what is applied and remove it. All
 * three only ever navigate.
 *
 * <p>The cursor is deliberately <em>not</em> in the URL. "Voir plus" extends the list in
 * place; putting the cursor in the address would make a shared link open on page three of
 * a list whose first two pages the recipient never saw.
 */
@Component({
  selector: 'app-search-page',
  imports: [SearchBar, ActiveFilters, SearchFilters, ViewToggle, ListingRow, ListingCard, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-page.html',
  styleUrl: './search-page.scss',
})
export class SearchPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly reference = inject(ReferenceStore);
  protected readonly results = inject(SearchResultsStore);
  private readonly preference = inject(ViewPreference);
  private readonly memory = inject(SearchMemory);
  private readonly resolver = inject(SearchResolver);
  private readonly recent = inject(RecentSearches);

  /**
   * True when this page was reached by going back or forward in history.
   *
   * <p>Read once, while the navigation that created the page is still the current one.
   * It decides whether the first search may reuse what the store already holds: yes when
   * the visitor is returning to a list they left, no for any fresh arrival. See
   * {@link SearchResultsStore.restore}.
   */
  private returning = this.router.getCurrentNavigation()?.trigger === 'popstate';

  /**
   * List or grid. A per-visitor preference rather than part of the search, so it comes
   * from {@link ViewPreference} and not from the URL: see that class for why.
   */
  protected readonly view = this.preference.view;

  protected readonly sortOptions = SORT_OPTIONS;
  /** Placeholder rows shown while the first page loads — about one screenful. */
  protected readonly skeletons = [0, 1, 2, 3, 4, 5];

  private readonly query$ = this.route.queryParamMap.pipe(map(parseQuery));

  protected readonly query = toSignal(this.query$, {
    initialValue: parseQuery(this.route.snapshot.queryParamMap),
  });

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected readonly hasFilters = computed(() => activeFilterCount(this.query()) > 0);

  /** "22 annonces", "Plus de 10 000 annonces", or nothing while the count is unknown. */
  protected readonly countText = computed(() => countLabel(this.results.count()));

  constructor() {
    this.query$
      .pipe(
        // Angular emits the param map again for navigations that did not change it. One
        // search per distinct query, compared by the canonical form of its URL.
        distinctUntilChanged((a, b) => canonical(a) === canonical(b)),
        // switchMap: a query that arrives while the previous one is still resolving its
        // make against the catalogue replaces it, rather than racing it.
        switchMap((query) =>
          this.resolver.criteria(query).pipe(map((criteria) => ({ query, criteria }))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ query, criteria }) => {
        const sort = query.sort;
        // So a listing opened from here knows which search to lead back to.
        this.memory.remember(this.router.url);
        // And so the home page can offer it again. Ignores a search with no filters.
        this.recent.record(query);

        if (this.returning) {
          // Only the search the page arrived with. Every later one on this page is a
          // change the visitor made, and is always asked of the API.
          this.returning = false;
          this.results.restore(criteria, sort);
        } else {
          this.results.search(criteria, sort);
        }
      });

    inject(DestroyRef).onDestroy(() => this.results.release());
  }

  protected applyFilters(patch: Partial<SearchQuery>): void {
    this.navigate({ ...this.query(), ...patch });
  }

  protected clearFilters(): void {
    this.navigate(withoutFilters(this.query()));
  }

  protected setView(view: ResultsView): void {
    this.preference.set(view);
  }

  protected setSort(event: Event): void {
    const sort = (event.target as HTMLSelectElement).value as SearchSort;
    this.navigate({ ...this.query(), sort });
  }

  protected minorUnitOf(listing: PublicListingSummary): number {
    return this.minorUnits()[listing.currencyCode ?? ''] ?? DEFAULT_MINOR_UNIT;
  }

  protected fuelLabel(listing: PublicListingSummary): string {
    return labelOf(FUEL_LABELS, listing.fuelType);
  }

  protected gearboxLabel(listing: PublicListingSummary): string {
    return labelOf(GEARBOX_LABELS, listing.gearbox);
  }

  /**
   * Replaces the whole query string with the canonical form of a query.
   *
   * <p>Replaced, not merged: a cleared filter has to disappear from the address, and
   * merging would leave it behind. A real navigation rather than `replaceUrl`, so each
   * filter change is a history entry and the back button undoes exactly one of them.
   */
  private navigate(query: SearchQuery): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: toQueryParams(query),
    });
  }

}

function canonical(query: SearchQuery): string {
  return JSON.stringify(toQueryParams(query));
}

/**
 * The heading above the list.
 *
 * <p>French agrees the noun with the number from two upward, so zero and one are
 * singular: "0 annonce", "1 annonce", "2 annonces".
 */
export function countLabel(count: ResultCount | null): string | null {
  if (count === null || typeof count.count !== 'number') {
    return null;
  }
  if (count.capped) {
    return `Plus de ${formatIntegerFr(count.count)} annonces`;
  }
  return `${formatIntegerFr(count.count)} ${count.count < 2 ? 'annonce' : 'annonces'}`;
}
