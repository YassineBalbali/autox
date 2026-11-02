import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatDateFr } from '../../shared/format/date';
import { formatIntegerFr } from '../../shared/format/number';

/** One day of a series. */
export interface DayCount {
  readonly day: string;
  readonly real: number;
  readonly demo: number;
}

/** One day as drawn: rectangles in the chart's own units, the demo stacked on the real. */
export interface DayBar {
  readonly day: string;
  readonly x: number;
  readonly realY: number;
  readonly realHeight: number;
  readonly demoY: number;
  readonly demoHeight: number;
  readonly label: string;
}

const HEIGHT = 80;
const SLOT = 10;
const BAR = 7;

/**
 * The rectangles for a series. Pure, so the arithmetic can be tested without a browser.
 *
 * <p>The scale follows what is shown. Demo is left out by default, and has to be: the seed lands
 * on one day, and on a shared scale its hundred and fifty listings would flatten every real bar
 * to nothing. A non-zero value is at least a pixel or two tall, so that one listing on a busy
 * scale still shows.
 */
export function dayBars(days: readonly DayCount[], includeDemo: boolean): { bars: DayBar[]; max: number } {
  const totalOf = (day: DayCount) => day.real + (includeDemo ? day.demo : 0);
  const max = days.reduce((largest, day) => Math.max(largest, totalOf(day)), 0);
  const height = (value: number) => (value === 0 ? 0 : Math.max(2, (value / max) * HEIGHT));

  const bars = days.map((day, index) => {
    const realHeight = height(day.real);
    const demoHeight = includeDemo ? height(day.demo) : 0;
    const date = formatDateFr(day.day) ?? day.day;
    return {
      day: day.day,
      x: index * SLOT + (SLOT - BAR) / 2,
      realY: HEIGHT - realHeight,
      realHeight,
      demoY: HEIGHT - realHeight - demoHeight,
      demoHeight,
      label: includeDemo
        ? `${date} : ${formatIntegerFr(day.real)} réel, ${formatIntegerFr(day.demo)} démo`
        : `${date} : ${formatIntegerFr(day.real)}`,
    };
  });
  return { bars, max };
}

/**
 * A month of one count, as bars. Hand-drawn SVG: thirty rectangles need no charting library, and
 * this page should not make everyone's bundle larger. Bars are rectangles with a `<title>`, for
 * the figure under the pointer, and the same figures are in a table for a screen reader.
 */
@Component({
  selector: 'app-daily-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="chart">
      <figcaption class="chart__title">{{ title() }}</figcaption>
      <div class="chart__plot">
        <span class="chart__max" aria-hidden="true">{{ maxLabel() }}</span>
        <svg
          class="chart__svg"
          [attr.viewBox]="viewBox()"
          preserveAspectRatio="none"
          aria-hidden="true"
          focusable="false"
        >
          @for (bar of drawn().bars; track bar.day) {
            <g>
              <title>{{ bar.label }}</title>
              <!-- The whole height of the slot, so that a day with nothing still has a tooltip. -->
              <rect class="chart__slot" [attr.x]="bar.x" y="0" width="7" height="80" />
              @if (bar.realHeight > 0) {
                <rect class="chart__real" [attr.x]="bar.x" [attr.y]="bar.realY" width="7" [attr.height]="bar.realHeight" />
              }
              @if (bar.demoHeight > 0) {
                <rect class="chart__demo" [attr.x]="bar.x" [attr.y]="bar.demoY" width="7" [attr.height]="bar.demoHeight" />
              }
            </g>
          }
        </svg>
      </div>
      <div class="chart__axis" aria-hidden="true">
        <span>{{ firstDay() }}</span>
        <span>{{ lastDay() }}</span>
      </div>
      @if (drawn().max === 0) {
        <p class="chart__empty" i18n="@@chartEmpty">Rien sur ces 30 jours.</p>
      }
      <table class="visually-hidden">
        <caption>{{ title() }}</caption>
        <thead>
          <tr>
            <th scope="col" i18n="@@chartDay">Jour</th>
            <th scope="col" i18n="@@chartReal">Réel</th>
            <th scope="col" i18n="@@chartDemo">Démo</th>
          </tr>
        </thead>
        <tbody>
          @for (day of days(); track day.day) {
            <tr>
              <td>{{ day.day }}</td>
              <td>{{ day.real }}</td>
              <td>{{ day.demo }}</td>
            </tr>
          }
        </tbody>
      </table>
    </figure>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }

    .chart {
      margin: 0;
    }

    .chart__title {
      margin-bottom: var(--s-2);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      color: var(--c-ink);
    }

    .chart__plot {
      position: relative;
      border-bottom: 1px solid var(--c-line-strong);
    }

    .chart__max {
      position: absolute;
      top: 0;
      left: 0;
      font-size: var(--t-2xs);
      color: var(--c-ink-faint);
      font-variant-numeric: tabular-nums;
      pointer-events: none;
    }

    .chart__svg {
      display: block;
      width: 100%;
      height: 120px;
    }

    .chart__slot {
      fill: transparent;

      &:hover {
        fill: var(--c-surface-sunk);
      }
    }

    .chart__real {
      fill: var(--c-accent);
      pointer-events: none;
    }

    .chart__demo {
      fill: var(--c-line-strong);
      pointer-events: none;
    }

    .chart__axis {
      display: flex;
      justify-content: space-between;
      margin-top: var(--s-1);
      font-size: var(--t-2xs);
      color: var(--c-ink-faint);
    }

    .chart__empty {
      margin: var(--s-1) 0 0;
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
    }
  `,
})
export class DailyChart {
  readonly title = input.required<string>();
  readonly days = input.required<readonly DayCount[]>();
  readonly includeDemo = input(false);

  protected readonly drawn = computed(() => dayBars(this.days(), this.includeDemo()));
  protected readonly viewBox = computed(() => `0 0 ${this.days().length * SLOT} ${HEIGHT}`);
  protected readonly maxLabel = computed(() => (this.drawn().max > 0 ? formatIntegerFr(this.drawn().max) : ''));
  protected readonly firstDay = computed(() => formatDateFr(this.days()[0]?.day) ?? '');
  protected readonly lastDay = computed(() => formatDateFr(this.days().at(-1)?.day) ?? '');
}
