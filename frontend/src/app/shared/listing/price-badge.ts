import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { PriceIndicator, PriceLevel } from '../../core/api';
import { PRICE_LEVEL_LABELS, labelOf } from './labels.fr';

/** The three levels, cheapest first: the order of the meter's segments. */
const LEVELS: readonly PriceLevel[] = [PriceLevel.GOOD_DEAL, PriceLevel.MARKET, PriceLevel.ABOVE_MARKET];

/**
 * Where a listing's price sits against the market: a three-segment meter, the listing's
 * segment lit, and the verdict in words.
 *
 * <p>The meter shows the position at a glance — left is cheaper, right dearer — and the
 * words say it outright, so the colour is never the only signal. The meter is hidden from
 * assistive technology, which reads the words.
 *
 * <p>Renders nothing when the listing has no indicator: too few comparables, a new car, or
 * a vehicle not in the catalogue. Silence is the honest answer then, not "Prix du marché".
 */
@Component({
  selector: 'app-price-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (level(); as current) {
      <span class="badge" [attr.data-level]="current">
        <span class="badge__meter" aria-hidden="true">
          @for (segment of levels; track segment) {
            <span class="badge__segment" [class.badge__segment--on]="segment === current"></span>
          }
        </span>
        <span class="badge__label">{{ label() }}</span>
      </span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    // Each level's colour, set once and used by the segment and the label alike.
    .badge {
      --level: var(--c-accent);

      display: inline-flex;
      align-items: center;
      gap: var(--s-2);
      color: var(--level);
      font-size: var(--t-xs);
      font-weight: var(--fw-bold);
      line-height: 1.2;
      white-space: nowrap;

      &[data-level='GOOD_DEAL'] {
        --level: var(--c-success);
      }

      &[data-level='ABOVE_MARKET'] {
        --level: var(--c-warn);
      }
    }

    .badge__meter {
      display: inline-flex;
      gap: 2px;
    }

    .badge__segment {
      width: 0.875rem;
      height: 0.375rem;
      background: var(--c-line);

      &:first-child {
        border-radius: 999px 0 0 999px;
      }

      &:last-child {
        border-radius: 0 999px 999px 0;
      }
    }

    .badge__segment--on {
      background: var(--level);
    }
  `,
})
export class PriceBadge {
  readonly indicator = input<PriceIndicator | null | undefined>();

  protected readonly levels = LEVELS;

  protected readonly level = computed(() => {
    const level = this.indicator()?.level;
    return level && LEVELS.includes(level) ? level : null;
  });

  protected readonly label = computed(() => labelOf(PRICE_LEVEL_LABELS, this.level()));
}
