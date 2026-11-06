import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { formatIntegerFr } from '../../../shared/format/number';
import { Icon } from '../../../shared/ui/icon';
import { IconName } from '../../../shared/ui/icons.data';
import { Trend } from './dashboard-model';
import { Sparkline } from './sparkline';
import { TrendBadge } from './trend-badge';

/** One figure of the period: its value, its move since the previous period, and its shape. */
@Component({
  selector: 'app-kpi-card',
  imports: [Icon, TrendBadge, Sparkline],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="kpi__head">
      <span class="kpi__icon"><app-icon [name]="icon()" [size]="16" /></span>
      <h2 class="kpi__label">{{ label() }}</h2>
    </header>
    <p class="kpi__value">{{ number(value()) }}</p>
    <p class="kpi__trend">
      <app-trend-badge [trend]="trend()" [versus]="versus()" />
      <span class="kpi__versus">{{ versus() }}</span>
    </p>
    <app-sparkline [values]="spark()" />
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--s-1);
      padding: var(--s-3) var(--s-3) var(--s-2);
      border: 1px solid var(--c-line-strong);
      border-radius: var(--r-lg);
      background: var(--c-surface);
    }

    .kpi__head {
      display: flex;
      align-items: center;
      gap: var(--s-2);
    }

    .kpi__icon {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      border-radius: 8px;
      background: var(--c-accent-soft);
      color: var(--c-accent);
    }

    .kpi__label {
      margin: 0;
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      color: var(--c-ink-soft);
    }

    .kpi__value {
      margin: 0;
      font-size: var(--t-2xl);
      font-weight: var(--fw-bold);
      line-height: 1.1;
      font-variant-numeric: tabular-nums;
    }

    .kpi__trend {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-1);
      margin: 0 0 var(--s-1);
    }

    .kpi__versus {
      font-size: var(--t-2xs);
      color: var(--c-ink-faint);
    }
  `,
})
export class KpiCard {
  readonly label = input.required<string>();
  readonly icon = input.required<IconName>();
  readonly value = input.required<number>();
  readonly trend = input.required<Trend>();
  readonly versus = input('');
  readonly spark = input<readonly number[]>([]);

  protected readonly number = formatIntegerFr;
}
