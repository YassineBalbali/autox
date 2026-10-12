import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, of, shareReplay, tap } from 'rxjs';

import { Country, Feature, Make, Model, ReferenceService } from '../api';

/**
 * Reference data — makes, models, enum values, countries — fetched once and shared.
 *
 * <p>This is data that changes when the catalogue is edited, not when a user does
 * anything, and half the application needs it: the search filters now, the listing form
 * next. So each list is requested at most once per page load, however many components
 * ask, and the answer is replayed to all of them.
 *
 * <p><b>A failure is not cached.</b> A plain `shareReplay` would replay the error to every
 * later subscriber for the life of the page, so one dropped connection at startup would
 * leave the filters empty until a reload. Here a failed request clears its slot, and the
 * next subscriber tries again.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceStore {
  private readonly api = inject(ReferenceService);

  private makes$: Observable<Make[]> | null = null;
  private enums$: Observable<Record<string, string[]>> | null = null;
  private countries$: Observable<Country[]> | null = null;
  private features$: Observable<Feature[]> | null = null;
  private readonly models$ = new Map<string, Observable<Model[]>>();

  /** Active makes, popular ones first — the order the API returns them in. */
  makes(): Observable<Make[]> {
    return (this.makes$ ??= this.once(
      () => this.api.makes(),
      () => (this.makes$ = null),
    ));
  }

  /** The models of one make. */
  models(makeId: string): Observable<Model[]> {
    let cached = this.models$.get(makeId);
    if (!cached) {
      cached = this.once(
        () => this.api.models({ makeId }),
        () => this.models$.delete(makeId),
      );
      this.models$.set(makeId, cached);
    }
    return cached;
  }

  /** Every enum's values, keyed by enum name: `FuelType`, `Gearbox`, … */
  enums(): Observable<Record<string, string[]>> {
    return (this.enums$ ??= this.once(
      () => this.api.enums().pipe(map((response) => response.values ?? {})),
      () => (this.enums$ = null),
    ));
  }

  /** Every equipment item: its code, its category, and its label per locale. */
  features(): Observable<Feature[]> {
    return (this.features$ ??= this.once(
      () => this.api.features(),
      () => (this.features$ = null),
    ));
  }

  countries(): Observable<Country[]> {
    return (this.countries$ ??= this.once(
      () => this.api.countries(),
      () => (this.countries$ = null),
    ));
  }

  /**
   * Decimal places per currency code, from the countries that use each.
   *
   * <p>Every amount in the API is an integer of the minor unit, and the exponent is not
   * a constant: dividing a zero-decimal currency by 100 shows a hundredth of the price.
   * An empty map when the reference data cannot be read; callers then use
   * {@link DEFAULT_MINOR_UNIT}, which is right for every market open today and is why it
   * is a fallback and not the rule.
   */
  minorUnits(): Observable<Readonly<Record<string, number>>> {
    return this.countries().pipe(
      map((countries) => {
        const units: Record<string, number> = {};
        for (const country of countries) {
          if (country.currencyCode && typeof country.currencyMinorUnit === 'number') {
            units[country.currencyCode] = country.currencyMinorUnit;
          }
        }
        return units;
      }),
      catchError(() => of({})),
    );
  }

  private once<T>(request: () => Observable<T>, forget: () => void): Observable<T> {
    return defer(request).pipe(
      tap({ error: forget }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }
}

/** Used only when the reference data could not say. See {@link ReferenceStore.minorUnits}. */
export const DEFAULT_MINOR_UNIT = 2;
