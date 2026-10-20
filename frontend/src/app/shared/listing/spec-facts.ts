import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { NBSP, formatIntegerFr } from '../format/number';
import { Icon } from '../ui/icon';
import { IconName } from '../ui/icons.data';

interface Fact {
  readonly icon: IconName;
  /** What the figure is, for a screen reader: the icon says it to everyone else. */
  readonly label: string;
  readonly text: string;
}

/** `line` runs the facts along one line, wrapping; `grid` stacks them two by two. */
export type SpecFactsLayout = 'line' | 'grid';

/**
 * A result's key facts, each behind its icon: year, mileage, fuel, gearbox, power.
 *
 * <p>The results counterpart of {@link SpecLine}, and in the same fixed order for the same
 * reason — a buyer scanning a page finds the mileage in the same place on every result.
 * Here each fact carries an icon, so it is found by its shape before it is read; the
 * compact `SpecLine` stays for the places with no room for icons.
 *
 * <p>A missing fact is skipped, not shown as a dash. The icons are decorative; each figure
 * is preceded by its name in text a screen reader reads ("Kilométrage : 62 400 km") and
 * the screen does not show.
 */
@Component({
  selector: 'app-spec-facts',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="facts" [class.facts--grid]="layout() === 'grid'">
      @for (fact of facts(); track fact.label) {
        <li class="facts__item">
          <app-icon class="facts__icon" [name]="fact.icon" [size]="16" />
          <span class="visually-hidden">{{ fact.label }}{{ NBSP }}: </span>
          <span class="facts__text">{{ fact.text }}</span>
        </li>
      }
    </ul>
  `,
  styles: `
    .facts {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-1) var(--s-4);
      margin: 0;
      padding: 0;
      list-style: none;
      color: var(--c-ink);
      font-size: var(--t-sm);
      line-height: var(--lh-snug);
      font-variant-numeric: tabular-nums;
    }

    .facts--grid {
      display: grid;
      // The first column holds the fuel, the longest fact ("Hybride rechargeable").
      grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
      gap: var(--s-1) var(--s-3);
    }

    .facts__item {
      display: flex;
      align-items: center;
      gap: var(--s-1);
      min-width: 0;
      white-space: nowrap;
    }

    .facts__icon {
      flex: none;
      color: var(--c-ink-faint);
    }

    .facts__text {
      overflow: hidden;
      text-overflow: ellipsis;
    }
  `,
})
export class SpecFacts {
  protected readonly NBSP = NBSP;

  readonly year = input<number | null | undefined>();
  readonly mileage = input<number | null | undefined>();
  /** `km` or `mi` — from the listing's country, never assumed. */
  readonly distanceUnit = input('km');
  /** The fuel, already in French. */
  readonly fuel = input<string | null | undefined>();
  /** An electric car gets a plug, not a pump. */
  readonly electric = input(false);
  /** The gearbox, already in French. */
  readonly gearbox = input<string | null | undefined>();
  readonly powerHp = input<number | null | undefined>();
  readonly layout = input<SpecFactsLayout>('line');

  protected readonly facts = computed(() => {
    const facts: Fact[] = [];
    const year = this.year();
    const mileage = this.mileage();
    const fuel = this.fuel();
    const gearbox = this.gearbox();
    const power = this.powerHp();

    if (year != null) {
      // Not grouped: a year is a label, and "2 019" is wrong.
      facts.push({ icon: 'calendar', label: 'Année', text: String(year) });
    }
    if (mileage != null) {
      facts.push({
        icon: 'gauge',
        label: 'Kilométrage',
        text: `${formatIntegerFr(mileage)}${NBSP}${this.distanceUnit()}`,
      });
    }
    if (fuel) {
      facts.push({ icon: this.electric() ? 'plug-zap' : 'fuel', label: 'Énergie', text: fuel });
    }
    if (gearbox) {
      facts.push({ icon: 'cog', label: 'Boîte', text: gearbox });
    }
    if (power != null) {
      facts.push({ icon: 'zap', label: 'Puissance', text: `${formatIntegerFr(power)}${NBSP}ch` });
    }
    return facts;
  });
}
