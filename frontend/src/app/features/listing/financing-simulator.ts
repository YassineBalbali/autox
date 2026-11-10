import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { FINANCING_LIMITS, simulateCredit } from '../../shared/listing/financing';
import { formatPrice } from '../../shared/listing/price';

/**
 * An example rate, to start from: a plausible car-loan rate, and labelled on the page as an
 * example and not an offer. The visitor changes it to the one they are quoted.
 */
export const EXAMPLE_RATE_PERCENT = 5.9;

/** The durations offered, in months: a year to seven, by year. */
export const DURATIONS = [12, 24, 36, 48, 60, 72, 84] as const;

/** The mandatory warning for consumer credit (Code de la consommation, art. L. 312-5). */
export const CREDIT_WARNING =
  'Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.';

/**
 * "Simuler un financement", on a listing for sale: what a loan for this car would cost a month
 * and in all, for a down payment, a duration and a rate the visitor chooses.
 *
 * <p>Computed in the browser by {@link simulateCredit} from what is typed; nothing is sent, kept
 * or asked for — no lead form, no contact details. The rate starts at an example, said to be one.
 * The legal warning and the "not an offer" line are always shown with the figures.
 */
@Component({
  selector: 'app-financing-simulator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="simulator">
      <div class="simulator__inputs">
        <div class="field simulator__field">
          <label for="apport" i18n="@@financingDownPayment">Apport ({{ symbol() }})</label>
          <input class="input" id="apport" inputmode="numeric" autocomplete="off" data-input="down-payment"
                 [value]="downPaymentText()" (input)="downPaymentText.set(value($event))" />
        </div>
        <div class="field simulator__field">
          <label for="duree" i18n="@@financingDuration">Durée</label>
          <select class="input" id="duree" data-input="months" (change)="months.set(+value($event))">
            @for (duration of durations; track duration) {
              <option [value]="duration" [selected]="duration === months()">{{ duration }} mois</option>
            }
          </select>
        </div>
        <div class="field simulator__field">
          <label for="taux" i18n="@@financingRate">Taux annuel (%)</label>
          <input class="input" id="taux" inputmode="decimal" autocomplete="off" data-input="rate"
                 aria-describedby="taux-exemple" [value]="rateText()" (input)="rateText.set(value($event))" />
          <p class="meta" id="taux-exemple" data-example-rate>
            <ng-container i18n="@@financingExampleRate">Taux d’exemple ({{ exampleRate }} %), pas une offre de crédit : remplacez-le par celui qu’on vous propose.</ng-container>
          </p>
        </div>
      </div>

      @if (error()) {
        <p class="simulator__error" data-financing-error>{{ error() }}</p>
      } @else if (simulation(); as result) {
        <dl class="simulator__result" aria-live="polite">
          <div class="simulator__figure simulator__figure--main">
            <dt i18n="@@financingMonthly">Mensualité</dt>
            <dd data-result="monthly">{{ money(result.monthly) }}<span class="simulator__unit"> /mois</span></dd>
          </div>
          <div class="simulator__figure">
            <dt i18n="@@financingBorrowed">Montant emprunté</dt>
            <dd data-result="borrowed">{{ money(borrowed()) }}</dd>
          </div>
          <div class="simulator__figure">
            <dt i18n="@@financingTotal">Montant total dû</dt>
            <dd data-result="total">{{ money(result.totalRepaid) }}</dd>
          </div>
          <div class="simulator__figure">
            <dt i18n="@@financingCost">Coût du crédit</dt>
            <dd data-result="cost">{{ money(result.creditCost) }}</dd>
          </div>
        </dl>
      }

      <p class="simulator__warning" data-credit-warning>{{ warning }}</p>
      <p class="meta" data-not-an-offer i18n="@@financingNotAnOffer">
        Simulation indicative, sans valeur d’offre de crédit : elle ne tient compte ni de l’assurance, ni des frais de
        dossier. Rien de ce que vous saisissez n’est enregistré ni transmis.
      </p>
    </div>
  `,
  styles: `
    .simulator {
      display: flex;
      flex-direction: column;
      gap: var(--s-4);
    }

    .simulator__inputs {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--s-3);
      align-items: start;
    }

    .simulator__field {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);

      label {
        font-size: var(--t-sm);
        color: var(--c-ink-soft);
      }

      .input {
        width: 100%;
      }

      p {
        margin: 0;
      }
    }

    .simulator__result {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: var(--s-3);
      margin: 0;
      padding: var(--s-3);
      border-radius: var(--r-md);
      background: var(--c-surface-sunk);
    }

    .simulator__figure {
      display: flex;
      flex-direction: column;
      gap: var(--s-0);

      dt {
        font-size: var(--t-sm);
        color: var(--c-ink-soft);
      }

      dd {
        margin: 0;
        font-size: var(--t-md);
        font-weight: var(--fw-semibold);
      }
    }

    .simulator__figure--main dd {
      font-size: var(--t-lg);
      font-weight: var(--fw-heavy);
      color: var(--c-navy);
    }

    .simulator__unit {
      font-size: var(--t-sm);
      font-weight: var(--fw-regular);
    }

    .simulator__warning {
      margin: 0;
      font-weight: var(--fw-semibold);
    }

    .simulator__error {
      margin: 0;
      color: var(--c-danger);
    }

    @media (max-width: 767.98px) {
      .simulator__inputs,
      .simulator__result {
        grid-template-columns: 1fr 1fr;
      }

      .simulator__inputs .simulator__field:last-child {
        grid-column: 1 / -1;
      }
    }
  `,
})
export class FinancingSimulator {
  /** The listing's price, in the currency's minor unit. */
  readonly price = input.required<number>();
  readonly currency = input('EUR');
  readonly minorUnit = input(2);

  protected readonly durations = DURATIONS;
  protected readonly exampleRate = String(EXAMPLE_RATE_PERCENT).replace('.', ',');
  protected readonly warning = CREDIT_WARNING;

  protected readonly downPaymentText = signal('0');
  protected readonly months = signal(48);
  protected readonly rateText = signal(String(EXAMPLE_RATE_PERCENT).replace('.', ','));

  protected readonly symbol = computed(() => (this.currency() === 'EUR' ? '€' : this.currency()));

  private readonly downPayment = computed(() => parseAmount(this.downPaymentText()));
  private readonly rate = computed(() => parseRate(this.rateText()));
  protected readonly borrowed = computed(() => {
    const down = this.downPayment();
    return down === null ? 0 : this.price() - down * 10 ** this.minorUnit();
  });

  /** Why there is no figure, in French: what to correct. */
  protected readonly error = computed(() => {
    const down = this.downPayment();
    const rate = this.rate();
    if (down === null) {
      return 'Saisissez un apport en euros, sans centimes.';
    }
    if (rate === null || rate > FINANCING_LIMITS.maxAnnualRatePercent) {
      return `Saisissez un taux entre 0 et ${FINANCING_LIMITS.maxAnnualRatePercent} %, par exemple 5,9.`;
    }
    if (this.borrowed() <= 0) {
      return 'Avec cet apport, il n’y a rien à financer.';
    }
    return null;
  });

  protected readonly simulation = computed(() =>
    simulateCredit({ principal: this.borrowed(), annualRatePercent: this.rate() ?? Number.NaN, months: this.months() }),
  );

  protected money(amount: number): string {
    return formatPrice(amount, this.currency(), this.minorUnit());
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }
}

/** "2 000", "2000", "" → 2000, 2000, 0; anything else null. Whole units, spaces allowed. */
export function parseAmount(text: string): number | null {
  const compact = text.replace(/[\s\u00a0\u202f]/g, '');
  if (compact === '') {
    return 0;
  }
  return /^\d{1,9}$/.test(compact) ? Number(compact) : null;
}

/** "5,9", "5.9", "0" → 5.9, 5.9, 0; anything else null. */
export function parseRate(text: string): number | null {
  const compact = text.trim().replace(',', '.');
  return /^\d{1,2}(\.\d{1,3})?$/.test(compact) ? Number(compact) : null;
}
