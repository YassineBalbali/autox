import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { LeasingType, LeasingView } from '../../core/api';
import { formatIntegerFr } from '../format/number';
import { CREDIT_MENTION, LLD_NOTE, LOA_WITHDRAWAL, paymentsAfterFirst } from './leasing.fr';
import { formatPrice } from './price';

/**
 * A leasing offer in full: what is paid, for how long, and what it comes to.
 *
 * <p>An LOA is consumer credit, so its terms are laid out as the representative example its
 * advertising has to carry, with the mention the code de la consommation requires. Article L. 312-8
 * wants the amount of the instalments, the total due and that mention in larger type than the other
 * financing details, which is what the {@code --major} rows and the mention's size are for: leave
 * them larger. docs/leasing.md says what this does and does not cover.
 */
@Component({
  selector: 'app-leasing-offer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let offer = leasing();
    <section class="offer" data-leasing-offer [attr.data-type]="offer.type">
      <p class="offer__type">
        <span class="tag tag--accent" data-offer-type>{{ typeLabel() }}</span>
        @if (isLoa()) {
          <span class="offer__heading" i18n="@@leasingExample">Exemple représentatif</span>
        }
      </p>

      <dl class="offer__terms">
        <div class="offer__row" data-term="first">
          <dt i18n="@@leasingFirstPayment">Premier loyer (apport)</dt>
          <dd>{{ money(offer.firstPayment) }}</dd>
        </div>
        <div class="offer__row offer__row--major" data-term="monthly">
          <dt>{{ monthlyLabel() }}</dt>
          <dd>{{ money(offer.monthlyPayment) }}</dd>
        </div>
        <div class="offer__row" data-term="duration">
          <dt i18n="@@leasingDuration">Durée</dt>
          <dd>{{ offer.durationMonths }} mois</dd>
        </div>
        <div class="offer__row" data-term="mileage">
          <dt i18n="@@leasingMileage">Kilométrage</dt>
          <dd>{{ number(offer.mileagePerYear ?? 0) }} km/an</dd>
        </div>
        @if (priceAmount() !== undefined) {
          <div class="offer__row" data-term="cash-price">
            <dt i18n="@@leasingCashPrice">Prix au comptant du véhicule</dt>
            <dd>{{ money(priceAmount()) }}</dd>
          </div>
        }
        @if (isLoa()) {
          <div class="offer__row" data-term="option">
            <dt i18n="@@leasingOption">Option d’achat finale</dt>
            <dd>{{ money(offer.optionPrice) }}</dd>
          </div>
        }
        <div class="offer__row offer__row--major" data-term="total">
          <dt>{{ totalLabel() }}</dt>
          <dd>{{ money(offer.totalCost) }}</dd>
        </div>
      </dl>

      @if (isLoa()) {
        <p class="offer__mention" data-mention>{{ mention }}</p>
        <p class="offer__note" data-withdrawal>{{ withdrawal }}</p>
      } @else {
        <p class="offer__note" data-lld-note>{{ lldNote }}</p>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .offer {
      display: grid;
      gap: var(--s-2);
    }

    .offer__type {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-2);
      margin: 0;
    }

    .offer__heading {
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      color: var(--c-ink-soft);
    }

    .offer__terms {
      margin: 0;
    }

    .offer__row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--s-3);
      padding: var(--s-1) 0;
      border-bottom: 1px solid var(--c-line);
      font-size: var(--t-sm);
      color: var(--c-ink-soft);

      dd {
        margin: 0;
        font-weight: var(--fw-semibold);
        color: var(--c-ink);
        white-space: nowrap;
      }
    }

    .offer__row--major {
      font-size: var(--t-body);
      font-weight: var(--fw-semibold);
      color: var(--c-ink);

      dd {
        font-size: var(--t-lg);
        font-weight: var(--fw-bold);
        color: var(--c-accent);
      }
    }

    .offer__mention {
      margin: 0;
      font-size: var(--t-body);
      font-weight: var(--fw-bold);
      color: var(--c-ink);
    }

    .offer__note {
      margin: 0;
      font-size: var(--t-xs);
      color: var(--c-ink-soft);
    }
  `,
})
export class LeasingOffer {
  readonly leasing = input.required<LeasingView>();
  /** The vehicle's cash price. */
  readonly priceAmount = input<number | undefined>();
  readonly currency = input('EUR');
  readonly minorUnit = input(2);

  protected readonly mention = CREDIT_MENTION;
  protected readonly withdrawal = LOA_WITHDRAWAL;
  protected readonly lldNote = LLD_NOTE;
  protected readonly number = formatIntegerFr;

  protected readonly isLoa = computed(() => this.leasing().type === LeasingType.LOA);
  protected readonly typeLabel = computed(() =>
    this.isLoa() ? 'Location avec option d’achat (LOA)' : 'Location longue durée (LLD)',
  );
  protected readonly monthlyLabel = computed(() => {
    const count = paymentsAfterFirst(this.leasing());
    return `Puis ${count} loyer${count > 1 ? 's' : ''} mensuel${count > 1 ? 's' : ''} TTC de`;
  });
  protected readonly totalLabel = computed(() =>
    this.isLoa() ? 'Montant total dû en cas d’achat' : 'Coût total de la location',
  );

  protected money(amount: number | undefined): string {
    return formatPrice(amount ?? 0, this.currency(), this.minorUnit());
  }
}
