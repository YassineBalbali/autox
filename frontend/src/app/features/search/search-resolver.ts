import { Injectable, inject } from '@angular/core';
import { Observable, catchError, combineLatest, map, of, switchMap, take } from 'rxjs';

import { ListingSearchCriteria, ListingsPublicService, ResultCount } from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { VehicleNames } from './active-filters';
import { ResolvedVehicle, SearchQuery, toCriteria } from './search-query';

/**
 * The currency the price filter is expressed in.
 *
 * <p>One market is open, and its currency is the euro; the filter's label says "€". When
 * a second currency is enabled this becomes a choice the user makes, and the constant is
 * where that starts.
 */
export const PRICE_FILTER_CURRENCY = 'EUR';

/**
 * Everything that turns a search as the URL describes it into what the API and the screen
 * need: catalogue ids for the slugs, display names for the chips, the API's criteria, and
 * a count.
 *
 * <p>One place, because three screens need it — the results, the active-filter chips and
 * the home page's tiles — and the rule for a make that is not in the catalogue has to be
 * the same in all of them.
 */
@Injectable({ providedIn: 'root' })
export class SearchResolver {
  private readonly reference = inject(ReferenceStore);
  private readonly api = inject(ListingsPublicService);

  /**
   * Works out whether the make and model in the URL are catalogue entries or free text.
   *
   * <p>The URL carries a slug — `marque=peugeot` — because that is what belongs in a link;
   * the API filters on an id. If the slug is in the catalogue the id is sent, which
   * matches exactly. If it is not — an empty catalogue, an old link, something typed — the
   * text is sent instead and the API matches it against the displayed name. Either way the
   * search runs: a catalogue that fails to load costs precision, not the page.
   */
  vehicle(query: Pick<SearchQuery, 'make' | 'model'>): Observable<ResolvedVehicle> {
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

  /**
   * The make and model as the catalogue names them — "Citroën", not "citroen".
   *
   * <p>The URL carries slugs, and a slug is not a label. Until the catalogue answers, and
   * if it never does, there is no name and the caller shows the slug: wrong in its
   * accents, right in what it means.
   */
  names(query: Pick<SearchQuery, 'make' | 'model'>): Observable<VehicleNames> {
    const { make, model } = query;
    if (!make) {
      return of<VehicleNames>({});
    }
    return this.reference.makes().pipe(
      switchMap((makes) => {
        const found = makes.find((candidate) => candidate.slug === make);
        if (!found?.id || !model) {
          return of<VehicleNames>({ make: found?.name });
        }
        return this.reference.models(found.id).pipe(
          map((models): VehicleNames => ({
            make: found.name,
            model: models.find((candidate) => candidate.slug === model)?.name,
          })),
          catchError(() => of<VehicleNames>({ make: found.name })),
        );
      }),
      catchError(() => of<VehicleNames>({})),
    );
  }

  /** The API's criteria for a query: ids resolved, prices in the currency's minor units. */
  criteria(query: SearchQuery): Observable<ListingSearchCriteria> {
    return combineLatest([this.vehicle(query), this.reference.minorUnits()]).pipe(
      take(1),
      map(([vehicle, units]) =>
        toCriteria(
          query,
          vehicle,
          PRICE_FILTER_CURRENCY,
          units[PRICE_FILTER_CURRENCY] ?? DEFAULT_MINOR_UNIT,
        ),
      ),
    );
  }

  /**
   * How many listings a query matches, or `null` if that cannot be found out.
   *
   * <p>Never an error: every caller shows a count as a nicety beside something that works
   * without it, and a failed count must cost the number, not the tile or the button.
   */
  count(query: SearchQuery): Observable<ResultCount | null> {
    return this.criteria(query).pipe(
      switchMap((criteria) => this.api.count({ criteria })),
      catchError(() => of(null)),
    );
  }
}
