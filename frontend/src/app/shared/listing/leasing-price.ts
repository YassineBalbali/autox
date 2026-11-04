import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { LeasingView } from '../../core/api';
import { formatIntegerFr } from '../format/number';
import { leasingTerms } from './leasing.fr';
import { formatPrice } from './price';

/** A leasing offer's price on a result: "à partir de 219 €/mois", and its terms under it. */
@Component({
  selector: 'app-leasing-price',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="lease" data-leasing-price>
      <span class="lease__from" i18n="@@leasingFrom">à partir de</span>&ngsp;
      <strong class="lease__amount">{{ monthly() }}</strong><span class="lease__unit" i18n="@@leasingPerMonth">/mois</span>
    </p>
    <p class="lease__terms" data-leasing-terms>{{ terms() }}</p>
  `,
  styles: `
    :host {
      display: block;
    }

    .lease {
      margin: 0;
      color: var(--c-accent);
      white-space: nowrap;
    }

    .lease__from,
    .lease__unit {
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
    }

    .lease__from {
      display: block;
      color: var(--c-ink-soft);
    }

    .lease__amount {
      font-size: 1.5rem;
      font-weight: var(--fw-bold);
      line-height: var(--lh-tight);
    }

    .lease__terms {
      margin: var(--s-0) 0 0;
      font-size: var(--t-xs);
      font-weight: var(--fw-semibold);
      color: var(--c-ink-soft);
      white-space: nowrap;
    }
  `,
})
export class LeasingPrice {
  readonly leasing = input.required<LeasingView>();
  readonly currency = input('EUR');
  readonly minorUnit = input(2);

  protected readonly monthly = computed(() =>
    formatPrice(this.leasing().monthlyPayment ?? 0, this.currency(), this.minorUnit()),
  );
  protected readonly terms = computed(() => leasingTerms(this.leasing(), formatIntegerFr));
}
