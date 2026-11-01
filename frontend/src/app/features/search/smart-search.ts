import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, combineLatest, map, of, shareReplay, switchMap, take } from 'rxjs';

import {
  ListingSearchCriteria,
  Make,
  Model,
  NaturalLanguageSearchService,
  PriceLevel,
} from '../../core/api';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { PRICE_FILTER_CURRENCY } from './search-resolver';
import { EMPTY_QUERY, SearchQuery } from './search-query';

/**
 * What the results page shows about a search that came from a description: the words, and
 * the parts of them that are not among the filters. Travels in the navigation's history
 * state, not in the URL — the URL holds filters, and a shared link is a search, not a
 * conversation.
 */
export interface Interpretation {
  readonly text: string;
  readonly notUnderstood: readonly string[];
}

/** The history-state key the results page reads {@link Interpretation} from. */
export const INTERPRETATION_STATE = 'interpretation';

/** The API's limit on a description. */
export const MAX_DESCRIPTION = 200;

/**
 * The API's criteria as a search the URL can carry: catalogue ids to slugs, minor units to
 * whole euros, the good-deal level to its flag.
 *
 * <p>Pure, and as strict as {@link parseQuery}: the criteria were checked by the server, but
 * a make the reference data does not know is dropped rather than sent as text, since a slug
 * that resolves to nothing would quietly become a text filter.
 */
export function queryFromCriteria(
  criteria: ListingSearchCriteria,
  makes: readonly Make[],
  models: readonly Model[],
  minorUnit: number,
): SearchQuery {
  const make = makes.find((candidate) => candidate.id === criteria.makeId && !!candidate.slug);
  const model = make
    ? models.find((candidate) => candidate.id === criteria.modelId && !!candidate.slug)
    : undefined;
  const euros = criteria.currency === PRICE_FILTER_CURRENCY;
  const whole = (minor: number | undefined) =>
    euros && minor !== undefined && minor !== null ? Math.round(minor / 10 ** minorUnit) : null;

  return {
    ...EMPTY_QUERY,
    make: make?.slug ?? null,
    model: model?.slug ?? null,
    yearMin: criteria.yearFrom ?? null,
    yearMax: criteria.yearTo ?? null,
    priceMin: whole(criteria.priceMin),
    priceMax: whole(criteria.priceMax),
    mileageMin: criteria.mileageMin ?? null,
    mileageMax: criteria.mileageMax ?? null,
    fuel: [...(criteria.fuelType ?? [])].sort(),
    gearbox: [...(criteria.gearbox ?? [])].sort(),
    body: [...(criteria.bodyType ?? [])].sort(),
    condition: [...(criteria.condition ?? [])].sort(),
    location: criteria.location ?? null,
    goodDeals: (criteria.priceLevel ?? []).includes(PriceLevel.GOOD_DEAL),
  };
}

/**
 * The interpretation in a history state, or null. History state survives a reload and can be
 * anything another page left there, so it is read as untrusted: strings only, bounded.
 */
export function readInterpretation(state: unknown): Interpretation | null {
  const candidate = (state as Record<string, unknown> | null)?.[INTERPRETATION_STATE] as
    | Record<string, unknown>
    | undefined;
  if (!candidate || typeof candidate['text'] !== 'string' || !Array.isArray(candidate['notUnderstood'])) {
    return null;
  }
  return {
    text: candidate['text'].slice(0, MAX_DESCRIPTION),
    notUnderstood: candidate['notUnderstood']
      .filter((part): part is string => typeof part === 'string')
      .slice(0, 6)
      .map((part) => part.slice(0, 60)),
  };
}

/**
 * Natural-language search: whether this server has it, and a description turned into a
 * search.
 */
@Injectable({ providedIn: 'root' })
export class SmartSearch {
  private readonly api = inject(NaturalLanguageSearchService);
  private readonly reference = inject(ReferenceStore);

  /**
   * Whether to show the feature at all. False until the server says otherwise, and false if
   * it cannot be asked: a feature that may not work is not offered.
   */
  readonly available = toSignal(
    this.api.capabilities().pipe(
      map((capabilities) => capabilities.interpret === true),
      catchError(() => of(false)),
      shareReplay({ bufferSize: 1, refCount: false }),
    ),
    { initialValue: false },
  );

  /** The description as a search, with what to show about it. Errors are the caller's. */
  interpret(text: string): Observable<{ query: SearchQuery; interpretation: Interpretation }> {
    const description = text.trim().replace(/\s+/g, ' ').slice(0, MAX_DESCRIPTION);
    return this.api.interpret({ interpretRequest: { query: description } }).pipe(
      switchMap((response) => {
        const criteria = response.criteria ?? {};
        const models$ = criteria.makeId
          ? this.reference.models(criteria.makeId).pipe(catchError(() => of([] as Model[])))
          : of([] as Model[]);
        return combineLatest([
          this.reference.makes().pipe(catchError(() => of([] as Make[]))),
          models$,
          this.reference.minorUnits().pipe(catchError(() => of({} as Record<string, number>))),
        ]).pipe(
          take(1),
          map(([makes, models, units]) => ({
            query: queryFromCriteria(
              criteria,
              makes,
              models,
              units[PRICE_FILTER_CURRENCY] ?? DEFAULT_MINOR_UNIT,
            ),
            interpretation: { text: description, notUnderstood: response.notUnderstood ?? [] },
          })),
        );
      }),
    );
  }
}
