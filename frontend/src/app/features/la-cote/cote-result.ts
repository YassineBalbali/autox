import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Params, RouterLink } from '@angular/router';

import { Valuation } from '../../core/api';
import { Price, formatPrice } from '../../shared/listing/price';
import { basisMileage, basisYears, comparables, scopeCaveat, windowSentence } from './la-cote';

/** Where La Cote's links go, worked out by the page. */
export interface CoteLinks {
  /** "Vendre ma voiture": the listing form's query, the car already described. */
  readonly sell: Params;
  /** "Voir les annonces similaires": the search's query. */
  readonly search: Params;
  /** The loosenings that would give an estimate, as this page's own query. */
  readonly wider: readonly { label: string; count: number; params: Params }[];
}

/**
 * La Cote's answer: an estimate with its range and what it rests on, or, with too few
 * comparables, the plain statement that there is no figure and the ways to widen the search.
 *
 * <p>No figure is ever shown from fewer than five comparables. When there are not enough, the
 * count is given, so the visitor can see how far off it is.
 */
@Component({
  selector: 'app-cote-result',
  imports: [RouterLink, Price],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cote-result.html',
  styleUrl: './cote-result.scss',
})
export class CoteResult {
  readonly valuation = input.required<Valuation>();
  readonly modelName = input.required<string>();
  readonly minorUnit = input(2);
  readonly links = input.required<CoteLinks>();

  protected readonly estimate = computed(() => this.valuation().estimate ?? null);
  protected readonly count = computed(() => this.valuation().comparableCount ?? 0);
  protected readonly minimum = computed(() => this.valuation().minimumComparables ?? 5);
  protected readonly caveat = computed(() => scopeCaveat(this.valuation().scope));
  protected readonly criteria = computed(() => windowSentence(this.valuation().window));
  protected readonly basis = computed(() => this.valuation().basis ?? null);
  protected readonly years = computed(() => (this.basis() ? basisYears(this.basis()!) : ''));
  protected readonly mileages = computed(() => (this.basis() ? basisMileage(this.basis()!) : ''));
  protected readonly counted = computed(() => comparables(this.count()));

  protected readonly low = computed(() => this.money(this.estimate()?.lowAmount));
  protected readonly high = computed(() => this.money(this.estimate()?.highAmount));

  private money(amount: number | undefined): string {
    return formatPrice(amount ?? 0, this.estimate()?.currencyCode ?? 'EUR', this.minorUnit());
  }
}
