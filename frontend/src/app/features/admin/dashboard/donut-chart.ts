import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatIntegerFr } from '../../../shared/format/number';

export interface Segment {
  readonly label: string;
  readonly value: number;
  /** A CSS colour, usually a custom property. */
  readonly color: string;
}

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/** Parts of a whole as a ring, the total in the middle, with its legend beside it. */
@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="donut">
      <svg viewBox="0 0 140 140" class="donut__ring" role="img" [attr.aria-label]="label()">
        <circle class="donut__track" cx="70" cy="70" [attr.r]="radius" />
        @for (arc of arcs(); track arc.label) {
          <circle
            class="donut__arc"
            cx="70"
            cy="70"
            [attr.r]="radius"
            [style.stroke]="arc.color"
            [attr.stroke-dasharray]="arc.length + ' ' + circumference"
            [attr.stroke-dashoffset]="-arc.offset"
          >
            <title>{{ arc.label }} : {{ arc.text }}</title>
          </circle>
        }
        <text x="70" y="68" text-anchor="middle" class="donut__total">{{ totalText() }}</text>
        <text x="70" y="86" text-anchor="middle" class="donut__caption">{{ caption() }}</text>
      </svg>
      <ul class="donut__legend" role="list">
        @for (segment of segments(); track segment.label) {
          <li>
            <span class="donut__swatch" [style.background]="segment.color" aria-hidden="true"></span>
            <span class="donut__name">{{ segment.label }}</span>
            <span class="donut__value">{{ number(segment.value) }}</span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .donut {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-4);
    }

    .donut__ring {
      width: 132px;
      height: 132px;
      flex: none;
      transform: rotate(-90deg);
    }

    .donut__ring text {
      transform: rotate(90deg);
      transform-origin: 70px 70px;
    }

    .donut__track {
      fill: none;
      stroke: var(--c-surface-sunk);
      stroke-width: 18;
    }

    .donut__arc {
      fill: none;
      stroke-width: 18;
    }

    .donut__total {
      font-size: 22px;
      font-weight: var(--fw-bold);
      fill: var(--c-ink);
      font-variant-numeric: tabular-nums;
    }

    .donut__caption {
      font-size: 11px;
      fill: var(--c-ink-faint);
    }

    .donut__legend {
      display: grid;
      gap: var(--s-1);
      flex: 1 1 10rem;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--t-sm);

      li {
        display: grid;
        grid-template-columns: 10px 1fr auto;
        align-items: center;
        gap: var(--s-2);
      }
    }

    .donut__swatch {
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }

    .donut__name {
      color: var(--c-ink-soft);
    }

    .donut__value {
      font-weight: var(--fw-semibold);
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class DonutChart {
  readonly segments = input.required<readonly Segment[]>();
  readonly caption = input('');
  readonly label = input('');

  protected readonly radius = RADIUS;
  protected readonly circumference = CIRCUMFERENCE;
  protected readonly number = formatIntegerFr;

  private readonly total = computed(() => this.segments().reduce((sum, segment) => sum + segment.value, 0));
  protected readonly totalText = computed(() => formatIntegerFr(this.total()));

  protected readonly arcs = computed(() => {
    const total = this.total();
    let offset = 0;
    return this.segments()
      .filter((segment) => segment.value > 0 && total > 0)
      .map((segment) => {
        const length = (segment.value / total) * CIRCUMFERENCE;
        const arc = { ...segment, length, offset, text: formatIntegerFr(segment.value) };
        offset += length;
        return arc;
      });
  });
}
