import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { NBSP, formatIntegerFr } from '../format/number';

/**
 * A vehicle's key facts as one compact run: `2019 · 84 500 km · Diesel · Manuelle · 150 ch`.
 *
 * <p>Always in that order — year, mileage, fuel, gearbox, power — because the value of the
 * line is that a buyer scanning forty rows finds the mileage in the same place in each.
 * A missing fact is skipped rather than shown as a dash: the API marks most of these
 * optional, and "· — ·" on every third row is noise where a shorter line is not.
 *
 * <p>Fuel and gearbox arrive as words, already in French. The enum-to-label mapping
 * belongs to the screen that has the enum; this component formats numbers and joins.
 *
 * <p>A list, not a string with dots in it, so a screen reader announces five items
 * rather than reading "point médian" between them. The separators are CSS.
 */
@Component({
  selector: 'app-spec-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="specs">
      @for (item of items(); track $index) {
        <li class="specs__item">{{ item }}</li>
      }
    </ul>
  `,
  styles: `
    .specs {
      display: flex;
      flex-wrap: wrap;
      margin: 0;
      padding: 0;
      list-style: none;
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      line-height: var(--lh-snug);
      font-variant-numeric: tabular-nums;
    }

    .specs__item {
      white-space: nowrap;
    }

    // After each item but the last, not before each but the first: when the line wraps
    // in a narrow card the dot then stays at the end of the first line, instead of
    // opening the second one.
    .specs__item:not(:last-child)::after {
      content: '·';
      margin-inline: var(--s-1);
      color: var(--c-ink-faint);
    }
  `,
})
export class SpecLine {
  readonly year = input<number | null | undefined>();
  readonly mileage = input<number | null | undefined>();
  /** `km` or `mi` — from the listing's country, never assumed. */
  readonly distanceUnit = input('km');
  readonly fuel = input<string | null | undefined>();
  readonly gearbox = input<string | null | undefined>();
  readonly powerHp = input<number | null | undefined>();

  protected readonly items = computed(() => {
    const items: string[] = [];
    const year = this.year();
    const mileage = this.mileage();
    const power = this.powerHp();

    if (year != null) {
      // Not grouped: a year is a label, and "2 019" is wrong.
      items.push(String(year));
    }
    if (mileage != null) {
      items.push(`${formatIntegerFr(mileage)}${NBSP}${this.distanceUnit()}`);
    }
    if (this.fuel()) {
      items.push(this.fuel()!);
    }
    if (this.gearbox()) {
      items.push(this.gearbox()!);
    }
    if (power != null) {
      items.push(`${formatIntegerFr(power)}${NBSP}ch`);
    }
    return items;
  });
}
