import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { visibleSpaces } from '../format/number';

/** `list` for a row in a list of vehicles; `detail` for the one price on a listing page. */
export type PriceSize = 'list' | 'detail';

/**
 * A price. The most prominent thing wherever it appears.
 *
 * <p><b>Every price in the application goes through this component</b>, which is how
 * "always bold, always accent, always the same" is a property of the code rather than a
 * convention people remember. Position is the caller's half of the bargain: in a list it
 * goes in `.row__aside`, the right-hand slot that sits at the same x on every row.
 *
 * <p>Takes the amount exactly as the API sends it — an integer of the currency's minor
 * unit, plus the ISO code. Dividing is done here, once, with the exponent the API supplies
 * per country; a screen that divided by 100 itself would be off by a factor of a hundred
 * the day a zero-decimal currency is enabled.
 *
 * <p>Whole amounts are shown without decimals — "18 990 €", not "18 990,00 €" — because
 * that is how a car price is written and two zeros are noise on every row.
 */
@Component({
  selector: 'app-price',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="price" [class.price--detail]="size() === 'detail'">{{ text() }}</span>`,
  styles: `
    :host {
      display: inline-block;
    }

    .price {
      color: var(--c-accent);
      font-size: var(--t-lg);
      font-weight: var(--fw-heavy);
      line-height: var(--lh-tight);
      // Digits of equal width, so a column of prices aligns on the units.
      font-variant-numeric: tabular-nums;
      // A price never breaks: "18 990" on one line and "€" on the next is not a price.
      white-space: nowrap;
    }

    .price--detail {
      font-size: var(--t-2xl);
    }
  `,
})
export class Price {
  /** The amount in minor units, as the API returns it. */
  readonly amountMinor = input.required<number>();

  /** ISO 4217. */
  readonly currency = input('EUR');

  /** Decimal places of the currency — `Country.currencyMinorUnit`. */
  readonly minorUnit = input(2);

  readonly size = input<PriceSize>('list');

  protected readonly text = computed(() =>
    formatPrice(this.amountMinor(), this.currency(), this.minorUnit()),
  );
}

/** Exported for the places that need a price as a string — a page title, an aria-label. */
export function formatPrice(amountMinor: number, currency: string, minorUnit = 2): string {
  const amount = amountMinor / 10 ** minorUnit;
  const whole = Number.isInteger(amount);

  return visibleSpaces(
    new Intl.NumberFormat('fr-FR', {
      style: 'currency',
      currency,
      minimumFractionDigits: whole ? 0 : minorUnit,
      maximumFractionDigits: whole ? 0 : minorUnit,
    }).format(amount),
  );
}
