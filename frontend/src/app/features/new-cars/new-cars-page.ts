import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';

import {
  ListingsPublicService,
  MakeMatchCount,
  PublicListingSummary,
  ResultCount,
  SearchSort,
  VehicleCondition,
} from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { PHONE, mediaQuery } from '../../core/ui/media';
import { formatIntegerFr } from '../../shared/format/number';
import { FUEL_LABELS, GEARBOX_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { ListingCard } from '../../shared/listing/listing-card';
import { ListingRow } from '../../shared/listing/listing-row';
import { HomeFinder } from '../home/home-finder';
import { ShortcutTile } from '../home/shortcut-tile';
import { SEARCH_PATH } from '../search/legacy-search-redirect';
import { EMPTY_QUERY, SearchQuery, toQueryParams } from '../search/search-query';
import { SearchResolver } from '../search/search-resolver';

/** "Neuf": the search's own condition filter, the one rule the API applies (new, from a professional). */
const NEW_CARS: Partial<SearchQuery> = { condition: [VehicleCondition.NEW] };
const NEW_QUERY: SearchQuery = { ...EMPTY_QUERY, ...NEW_CARS };

/** How many of the latest arrivals the page shows: two rows of cards. */
const LATEST = 6;

/**
 * New and zero-kilometre cars from professionals.
 *
 * <p>Not a second search: the search card is the home page's, preset to "neuf"; the makes and
 * their counts come from the API with the search's own filters (`GET /listings/makes`), so each
 * tile's count is what its link then lists; the latest arrivals are the search's first page.
 * Every link lands on `/recherche` with `etat=new`, where the rest of the filters are.
 */
@Component({
  selector: 'app-new-cars-page',
  imports: [HomeFinder, ShortcutTile, ListingCard, ListingRow, RouterLink],
  templateUrl: './new-cars-page.html',
  styleUrl: './new-cars-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewCarsPage {
  private readonly api = inject(ListingsPublicService);
  private readonly resolver = inject(SearchResolver);
  private readonly reference = inject(ReferenceStore);

  protected readonly preset = NEW_CARS;
  protected readonly searchPath = SEARCH_PATH;
  protected readonly allParams = toQueryParams(NEW_QUERY);
  protected readonly phone = mediaQuery(PHONE, false);

  /** The makes with new stock, most first; empty on a failure, which hides the section. */
  protected readonly makes = toSignal(
    this.resolver.criteria(NEW_QUERY).pipe(
      switchMap((criteria) => this.api.makeCounts({ criteria })),
      catchError(() => of([] as MakeMatchCount[])),
    ),
    { initialValue: [] as MakeMatchCount[] },
  );

  /** The newest first page of the new-car search; `undefined` while it loads. */
  protected readonly latest = toSignal(
    this.resolver.criteria(NEW_QUERY).pipe(
      switchMap((criteria) => this.api.search({ criteria, sort: SearchSort.NEWEST, size: LATEST })),
      map((page) => page.items ?? []),
      catchError(() => of([] as PublicListingSummary[])),
    ),
  );

  protected readonly total = toSignal(this.resolver.count(NEW_QUERY));

  protected readonly allLabel = computed(() => {
    const total = this.total();
    return total && typeof total.count === 'number'
      ? `Voir les ${total.capped ? '+ de ' : ''}${formatIntegerFr(total.count)} voitures neuves`
      : 'Voir toutes les voitures neuves';
  });

  protected readonly empty = computed(() => this.latest()?.length === 0 && this.makes().length === 0);

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected makeParams(make: MakeMatchCount) {
    return toQueryParams({ ...NEW_QUERY, make: make.slug ?? null });
  }

  protected makeCount(make: MakeMatchCount): ResultCount {
    return { count: make.count, capped: false };
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
}
