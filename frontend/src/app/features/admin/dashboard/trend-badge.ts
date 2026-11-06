import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Icon } from '../../../shared/ui/icon';
import { IconName } from '../../../shared/ui/icons.data';
import { Trend, toneOf } from './dashboard-model';

/**
 * A figure's move since the previous period: an arrow, the percentage, and a colour that says
 * whether it is good news. The colour is never the only signal: the arrow and the sign say it too.
 */
@Component({
  selector: 'app-trend-badge',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="trend" [class]="'trend trend--' + tone()" [attr.title]="versus()" data-trend>
      @if (icon(); as name) {
        <app-icon [name]="name" [size]="14" [strokeWidth]="2.25" />
      }
      <span>{{ trend().label }}</span>
      <span class="visually-hidden">{{ versus() }}</span>
    </span>
  `,
  styles: `
    .trend {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      padding: 1px 6px;
      border-radius: 999px;
      font-size: var(--t-xs);
      font-weight: var(--fw-semibold);
      line-height: 1.5;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    }

    .trend--good {
      background: var(--c-success-soft);
      color: var(--c-success);
    }

    .trend--bad {
      background: var(--c-danger-soft);
      color: var(--c-danger);
    }

    .trend--neutral {
      background: var(--c-surface-sunk);
      color: var(--c-ink-soft);
    }
  `,
})
export class TrendBadge {
  readonly trend = input.required<Trend>();
  /** False for what should go down, such as failures. */
  readonly goodWhenUp = input(true);
  /** "vs les 7 jours précédents", for a screen reader and a tooltip. */
  readonly versus = input('');

  protected readonly tone = computed(() => toneOf(this.trend(), this.goodWhenUp()));
  protected readonly icon = computed<IconName | null>(() => {
    switch (this.trend().kind) {
      case 'up':
      case 'new':
        return 'trending-up';
      case 'down':
        return 'trending-down';
      case 'flat':
        return 'minus';
      default:
        return null;
    }
  });
}
