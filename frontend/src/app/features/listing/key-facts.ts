import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { PublicListingDetail } from '../../core/api';
import { NBSP, formatIntegerFr } from '../../shared/format/number';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons.data';

interface KeyFact {
  readonly icon: IconName;
  readonly label: string;
  readonly value: string;
}

const MONTH_AND_YEAR = new Intl.DateTimeFormat('fr-FR', { month: '2-digit', year: 'numeric' });

/**
 * The five facts a buyer weighs a car by, as tiles under the photos: year, mileage, fuel,
 * gearbox, power.
 *
 * <p>The same five, in the same order, as on a search result, so what was compared in the
 * list is found again at a glance here. The full specification follows further down;
 * this is the summary of it. A missing fact is left out rather than shown as a dash.
 *
 * <p>A definition list: each label is announced with its value, and the icon, which only
 * repeats the label, is hidden from assistive technology.
 */
@Component({
  selector: 'app-key-facts',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (facts().length) {
      <dl class="facts">
        @for (fact of facts(); track fact.label) {
          <div class="facts__tile">
            <dt class="facts__label">
              <span class="facts__icon"><app-icon [name]="fact.icon" [size]="22" /></span>
              {{ fact.label }}
            </dt>
            <dd class="facts__value">{{ fact.value }}</dd>
          </div>
        }
      </dl>
    }
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--s-2);
      margin: 0;

      @media (min-width: t.$bp-md) {
        grid-template-columns: repeat(5, minmax(0, 1fr));
      }
    }

    // Icon on top, then the label, then the value: read top to bottom, but the value is
    // what the eye lands on.
    .facts__tile {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--s-0);
      padding: var(--s-3);
      background: var(--c-surface);
      border: 1px solid var(--c-line);
      border-radius: var(--r-lg);
      box-shadow: var(--shadow-raised);
      min-width: 0;
    }

    .facts__icon {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 2.5rem;
      height: 2.5rem;
      margin-bottom: var(--s-2);
      border-radius: 50%;
      background: var(--c-accent-soft);
      color: var(--c-accent);
    }

    .facts__label {
      color: var(--c-ink-soft);
      font-size: var(--t-xs);
    }

    // Wraps rather than cuts: "Hybride rechargeable" is the value, not "Hybride recharg…".
    .facts__value {
      margin: 0;
      font-size: var(--t-md);
      font-weight: var(--fw-bold);
      line-height: var(--lh-snug);
      font-variant-numeric: tabular-nums;
    }

    // ---- a phone: one panel, the facts two by two --------------------------------
    //
    // Five tiles stacked two to a row take most of a phone's screen for five words. Below
    // the medium breakpoint they share one panel instead, each a small icon beside its
    // label and value. The dt steps aside (display: contents) so the icon and the label
    // are placed in the grid themselves: icon on the left over both lines, label above
    // value on the right.
    @media (max-width: calc(t.$bp-md - 0.02px)) {
      .facts {
        gap: var(--s-3) var(--s-4);
        padding: var(--s-3) var(--s-4);
        background: var(--c-surface);
        border: 1px solid var(--c-line);
        border-radius: var(--r-lg);
        box-shadow: var(--shadow-raised);
      }

      .facts__tile {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        column-gap: var(--s-2);
        align-items: center;
        // Label and value stay together at the top when a neighbour's value wraps.
        align-content: start;
        padding: 0;
        background: none;
        border: 0;
        border-radius: 0;
        box-shadow: none;
      }

      .facts__label {
        display: contents;
      }

      .facts__icon {
        grid-row: span 2;
        width: 2rem;
        height: 2rem;
        margin: 0;
      }

      .facts__value {
        font-size: var(--t-body);
      }
    }
  `,
})
export class KeyFacts {
  readonly listing = input.required<PublicListingDetail>();
  /** The fuel, already in French. */
  readonly fuel = input<string | null>(null);
  /** The gearbox, already in French. */
  readonly gearbox = input<string | null>(null);

  protected readonly facts = computed(() => {
    const listing = this.listing();
    const vehicle = listing.vehicle;
    const facts: KeyFact[] = [];

    const registered = registration(vehicle?.firstRegistrationYear, vehicle?.firstRegistrationMonth);
    if (registered) {
      facts.push({ icon: 'calendar', label: 'Année', value: registered });
    }
    if (listing.mileageKm != null) {
      facts.push({ icon: 'gauge', label: 'Kilométrage', value: `${formatIntegerFr(listing.mileageKm)}${NBSP}km` });
    }
    if (this.fuel()) {
      const electric = vehicle?.fuelType === 'ELECTRIC';
      facts.push({ icon: electric ? 'plug-zap' : 'fuel', label: 'Énergie', value: this.fuel()! });
    }
    if (this.gearbox()) {
      facts.push({ icon: 'cog', label: 'Boîte de vitesses', value: this.gearbox()! });
    }
    if (vehicle?.powerHp != null) {
      facts.push({ icon: 'zap', label: 'Puissance', value: `${formatIntegerFr(vehicle.powerHp)}${NBSP}ch` });
    }
    return facts;
  });
}

/** "10/2019" when the month is known, "2019" when it is not. */
export function registration(year: number | null | undefined, month: number | null | undefined): string | null {
  if (year == null) {
    return null;
  }
  if (month == null || month < 1 || month > 12) {
    // Not grouped: a year is a label, and "2 019" is wrong.
    return String(year);
  }
  return MONTH_AND_YEAR.format(new Date(year, month - 1, 1));
}
