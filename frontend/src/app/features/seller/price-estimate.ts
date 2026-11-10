import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';

import { FuelType, LaCoteService, PriceLevel, Valuation, ValuationPriceBounds } from '../../core/api';
import { countFr } from '../../shared/format/plural';
import { PRICE_LEVEL_LABELS } from '../../shared/listing/labels.fr';
import { formatPrice } from '../../shared/listing/price';
import { PriceBadge } from '../../shared/listing/price-badge';

/** The car being priced, as the vehicle step has it. */
export interface EstimatedCar {
  /** A catalogue model's id, or null when the model was typed: La Cote needs the catalogue's. */
  readonly modelId: string | null;
  readonly fuel: string | null;
  readonly powerHp: number | null;
  readonly year: number | null;
  readonly mileageKm: number | null;
  readonly isNew: boolean;
  readonly damaged: boolean;
  readonly leasing: boolean;
}

/**
 * Where a price sits against the badge's lines: the same comparison as the listing's badge, on
 * thresholds La Cote computed with the badge's own expressions (`MarketPrice.goodDealAtMost`,
 * `aboveMarketFrom`). At or below the first, a good deal; at or above the second, above the
 * market; between them, the market price.
 */
export function levelForPrice(priceMinor: number, bounds: ValuationPriceBounds): PriceLevel | null {
  const low = bounds.goodDealAtMost;
  const high = bounds.aboveMarketFrom;
  if (typeof low !== 'number' || typeof high !== 'number' || !Number.isFinite(priceMinor)) {
    return null;
  }
  if (priceMinor <= low) {
    return PriceLevel.GOOD_DEAL;
  }
  return priceMinor >= high ? PriceLevel.ABOVE_MARKET : PriceLevel.MARKET;
}

/** Why a car gets no estimate at all, before La Cote is asked; null when it can be asked. */
export function notEstimated(car: EstimatedCar): 'excluded' | 'new' | 'incomplete' | null {
  if (car.damaged || car.leasing) {
    // Never compared: a damaged car's price says nothing of the market, a lease's is not a price.
    return 'excluded';
  }
  if (car.isNew) {
    return 'new';
  }
  if (!car.modelId || !car.fuel || !car.powerHp || !car.year || car.mileageKm === null) {
    return 'incomplete';
  }
  return null;
}

type Answer = { state: 'loading' } | { state: 'ready'; valuation: Valuation } | { state: 'error' };

/**
 * On the price step: what La Cote estimates this car at, and where the price being typed sits.
 *
 * <p>La Cote itself (`GET /api/v1/valuation`), under the badge's own rule, not a copy of it: the
 * range is its estimate, and the verdict on the price is the badge's, from the lines La Cote
 * returns. With too few comparables it says so, and gives no figure. Nothing for a damaged car or
 * a leasing offer, which are never compared. The seller's price is theirs: this only informs it.
 */
@Component({
  selector: 'app-price-estimate',
  imports: [RouterLink, PriceBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @switch (reason()) {
      @case ('excluded') {}
      @case ('new') {
        <p class="estimate meta" data-estimate="new" i18n="@@estimateNew">
          La Cote n’estime pas les véhicules neufs : fixez votre prix librement.
        </p>
      }
      @case ('incomplete') {
        <p class="estimate meta" data-estimate="incomplete" i18n="@@estimateIncomplete">
          Pour voir l’estimation de La Cote, choisissez le modèle dans la liste et indiquez le carburant et la
          puissance à l’étape « Véhicule ».
        </p>
      }
      @default {
        <section class="estimate" aria-labelledby="estimation-titre" aria-live="polite" data-estimate="la-cote">
          <h3 id="estimation-titre" class="estimate__title" i18n="@@estimateTitle">Estimation La Cote</h3>
          @switch (answer().state) {
            @case ('loading') {
              <p class="meta" aria-busy="true" i18n="@@estimateLoading">Estimation en cours…</p>
            }
            @case ('error') {
              <p class="meta" data-estimate-error i18n="@@estimateError">
                L’estimation n’a pas pu être calculée. Vous pouvez fixer votre prix sans elle.
              </p>
            }
            @case ('ready') {
              @if (estimate(); as value) {
                <p class="estimate__figure" data-estimate-median>{{ money(value.medianAmount!) }}</p>
                <p class="meta" data-estimate-range>
                  <ng-container i18n="@@estimateRange">Entre</ng-container> {{ money(value.lowAmount!) }}
                  <ng-container i18n="@@estimateAnd">et</ng-container> {{ money(value.highAmount!) }},
                  {{ basedOn() }}.
                </p>
                @if (priceLevel(); as level) {
                  <p class="estimate__verdict" data-estimate-level>
                    <span i18n="@@estimateYourPrice">Votre prix :</span>
                    <app-price-badge [indicator]="{ level }" />
                  </p>
                }
              } @else {
                <p data-estimate="too-few">
                  <ng-container i18n="@@estimateTooFew">Pas assez d’annonces comparables sur AutoX pour estimer ce véhicule</ng-container>
                  ({{ found() }}, {{ minimum() }} <ng-container i18n="@@estimateNeeded">nécessaires</ng-container>).
                  <ng-container i18n="@@estimateTooFewFree">Fixez votre prix librement.</ng-container>
                </p>
              }
            }
          }
          <p class="meta" i18n="@@estimateFree">
            Le prix reste le vôtre : l’estimation ne l’impose pas. Elle repose sur les prix demandés sur AutoX,
            pas sur des ventes conclues.
          </p>
          <a class="meta" routerLink="/la-cote" i18n="@@estimateMore">Comment La Cote estime</a>
        </section>
      }
    }
  `,
  styles: `
    .estimate {
      display: flex;
      flex-direction: column;
      gap: var(--s-2);
      margin: 0;
      padding: var(--s-3);
      border: var(--divider);
      border-radius: var(--r-md);
      background: var(--c-surface-sunk);

      p {
        margin: 0;
      }
    }

    p.estimate {
      background: none;
      border: 0;
      padding: 0;
    }

    .estimate__title {
      margin: 0;
      font-size: var(--t-body);
      font-weight: var(--fw-semibold);
    }

    .estimate__figure {
      font-size: var(--t-lg);
      font-weight: var(--fw-heavy);
      color: var(--c-navy);
    }

    .estimate__verdict {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-2);
    }
  `,
})
export class PriceEstimate {
  private readonly laCote = inject(LaCoteService);

  readonly car = input.required<EstimatedCar>();
  /** What the seller has typed, in whole units; empty or not a number gives no verdict. */
  readonly price = input<string>('');
  readonly currency = input('EUR');
  readonly minorUnit = input(2);

  protected readonly reason = computed(() => notEstimated(this.car()));

  /** One request per car, once the seller has stopped typing in the vehicle step. */
  private readonly answer$ = toObservable(
    computed(() => {
      const car = this.car();
      return notEstimated(car) === null
        ? JSON.stringify([car.modelId, car.fuel, car.powerHp, car.year, car.mileageKm, this.currency()])
        : null;
    }),
  ).pipe(
    distinctUntilChanged(),
    debounceTime(300),
    switchMap((key): Observable<Answer> => {
      if (key === null) {
        return of({ state: 'loading' });
      }
      const car = this.car();
      return this.laCote
        .value({
          car: {
            modelId: car.modelId!,
            fuelType: car.fuel as FuelType,
            powerHp: car.powerHp!,
            year: car.year!,
            mileageKm: car.mileageKm!,
            currency: this.currency(),
          },
        })
        .pipe(
          map((valuation): Answer => ({ state: 'ready', valuation })),
          catchError(() => of<Answer>({ state: 'error' })),
          startWith<Answer>({ state: 'loading' }),
        );
    }),
  );

  protected readonly answer = toSignal(this.answer$, { initialValue: { state: 'loading' } as Answer });

  private readonly valuation = computed(() => {
    const answer = this.answer();
    return answer.state === 'ready' ? answer.valuation : null;
  });
  protected readonly estimate = computed(() => this.valuation()?.estimate ?? null);
  protected readonly found = computed(() =>
    countFr(this.valuation()?.comparableCount ?? 0, 'annonce comparable trouvée', 'annonces comparables trouvées'),
  );
  protected readonly minimum = computed(() => this.valuation()?.minimumComparables ?? 5);
  protected readonly basedOn = computed(
    () => `d’après ${countFr(this.valuation()?.comparableCount ?? 0, 'annonce comparable', 'annonces comparables')}`,
  );

  /** The badge this price would get, live as it is typed. */
  protected readonly priceLevel = computed(() => {
    const bounds = this.valuation()?.priceBounds;
    const typed = this.price().replace(/[\s\u00a0\u202f]/g, '');
    if (!bounds || !/^\d{1,9}$/.test(typed)) {
      return null;
    }
    return levelForPrice(Number(typed) * 10 ** this.minorUnit(), bounds);
  });

  protected readonly levelLabel = computed(() => {
    const level = this.priceLevel();
    return level ? PRICE_LEVEL_LABELS[level] : null;
  });

  protected money(amount: number): string {
    return formatPrice(amount, this.currency(), this.minorUnit());
  }
}
