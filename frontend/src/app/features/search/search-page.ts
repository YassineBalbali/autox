import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, catchError, combineLatest, distinctUntilChanged, map, of, switchMap, take } from 'rxjs';

import { PublicListingSummary, ResultCount, SearchSort } from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { formatIntegerFr } from '../../shared/format/number';
import { ListingRow } from '../../shared/listing/listing-row';
import { Alert } from '../../shared/ui/alert';
import { FUEL_LABELS, GEARBOX_LABELS, SORT_OPTIONS, labelOf } from './labels.fr';
import { SearchFilters } from './search-filters';
import {
  ResolvedVehicle,
  SearchQuery,
  activeFilterCount,
  parseQuery,
  toCriteria,
  toQueryParams,
  withoutFilters,
} from './search-query';
import { SearchResultsStore } from './search-results.store';

/**
 * The currency the price filter is expressed in.
 *
 * <p>One market is open, and its currency is the euro; the filter's label says "€". When
 * a second currency is enabled this becomes a choice the user makes, and the constant is
 * the one place that has to learn about it.
 */
const PRICE_FILTER_CURRENCY = 'EUR';

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
 * <p>The cursor is deliberately <em>not</em> in the URL. "Voir plus" extends the list in
 * place; putting the cursor in the address would make a shared link open on page three of
 * a list whose first two pages the recipient never saw.
 */
@Component({
  selector: 'app-search-page',
  imports: [SearchFilters, ListingRow, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SearchResultsStore],
  templateUrl: './search-page.html',
  styleUrl: './search-page.scss',
})
export class SearchPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly reference = inject(ReferenceStore);
  protected readonly results = inject(SearchResultsStore);

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
          combineLatest([this.resolveVehicle(query), this.reference.minorUnits()]).pipe(
            take(1),
            map(([vehicle, units]) => ({
              sort: query.sort,
              criteria: toCriteria(
                query,
                vehicle,
                PRICE_FILTER_CURRENCY,
                units[PRICE_FILTER_CURRENCY] ?? DEFAULT_MINOR_UNIT,
              ),
            })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ criteria, sort }) => this.results.search(criteria, sort));
  }

  protected applyFilters(patch: Partial<SearchQuery>): void {
    this.navigate({ ...this.query(), ...patch });
  }

  protected clearFilters(): void {
    this.navigate(withoutFilters(this.query()));
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

  /**
   * Works out whether the make and model in the URL are catalogue entries or free text.
   *
   * <p>The URL carries a slug — `marque=peugeot` — because that is what belongs in a link;
   * the API filters on an id. If the slug is in the catalogue the id is sent, which
   * matches exactly. If it is not — an empty catalogue, an old link, something typed — the
   * text is sent instead and the API matches it against the displayed name. Either way the
   * search runs: a catalogue that fails to load costs precision, not the page.
   */
  private resolveVehicle(query: SearchQuery): Observable<ResolvedVehicle> {
    if (!query.make) {
      return of({});
    }
    const asText: ResolvedVehicle = { makeText: query.make, modelText: query.model ?? undefined };

    return this.reference.makes().pipe(
      switchMap((makes) => {
        const make = makes.find((candidate) => candidate.slug === query.make);
        if (!make?.id) {
          return of(asText);
        }
        if (!query.model) {
          return of<ResolvedVehicle>({ makeId: make.id });
        }
        return this.reference.models(make.id).pipe(
          map((models): ResolvedVehicle => {
            const model = models.find((candidate) => candidate.slug === query.model);
            return model?.id
              ? { makeId: make.id, modelId: model.id }
              : { makeId: make.id, modelText: query.model ?? undefined };
          }),
          catchError(() => of<ResolvedVehicle>({ makeId: make.id, modelText: query.model ?? undefined })),
        );
      }),
      catchError(() => of(asText)),
    );
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
