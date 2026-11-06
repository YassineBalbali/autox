import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatIntegerFr } from '../../../shared/format/number';

export interface Column {
  /** Under the axis: "5 nov.", "14 h". */
  readonly label: string;
  /** In the tooltip and the table: "mercredi 5 novembre". */
  readonly title: string;
  readonly real: number;
  readonly demo: number;
}

const WIDTH = 720;
const HEIGHT = 220;
const LEFT = 40;
const RIGHT = 8;
const TOP = 10;
const BOTTOM = 26;

/** A round top for the axis: 1, 2, 5, 10, 20, 50… times the power of ten, at least 4. */
export function niceMax(value: number): number {
  if (value <= 4) {
    return 4;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * power >= value) ?? 10;
  return step * power;
}

/**
 * Activity per hour or per day, as columns: real activity in the accent colour, the demo seed
 * stacked above it in grey when it is shown. SVG drawn here rather than a library: a few dozen
 * rectangles do not need one, and the public site's bundle is not asked to carry it.
 *
 * <p>For a screen reader, the same figures as a table.
 */
@Component({
  selector: 'app-column-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="chart"
      [attr.viewBox]="'0 0 ' + width + ' ' + height"
      role="img"
      [attr.aria-label]="label()"
      preserveAspectRatio="xMidYMid meet"
    >
      @for (tick of ticks(); track tick.value) {
        <line class="chart__grid" [attr.x1]="left" [attr.x2]="width - right" [attr.y1]="tick.y" [attr.y2]="tick.y" />
        <text class="chart__tick" [attr.x]="left - 6" [attr.y]="tick.y + 4" text-anchor="end">{{ tick.text }}</text>
      }
      @for (bar of bars(); track $index) {
        <g class="chart__bar">
          <title>{{ bar.tooltip }}</title>
          <rect class="chart__hit" [attr.x]="bar.slotX" [attr.y]="top" [attr.width]="bar.slot" [attr.height]="plotHeight" />
          @if (bar.realHeight > 0) {
            <rect class="chart__real" [attr.x]="bar.x" [attr.y]="bar.realY" [attr.width]="bar.width" [attr.height]="bar.realHeight" rx="1.5" />
          }
          @if (bar.demoHeight > 0) {
            <rect class="chart__demo" [attr.x]="bar.x" [attr.y]="bar.demoY" [attr.width]="bar.width" [attr.height]="bar.demoHeight" rx="1.5" />
          }
          @if (bar.showLabel) {
            <text class="chart__label" [attr.x]="bar.x + bar.width / 2" [attr.y]="height - 8" text-anchor="middle">{{ bar.label }}</text>
          }
        </g>
      }
      <line class="chart__axis" [attr.x1]="left" [attr.x2]="width - right" [attr.y1]="top + plotHeight" [attr.y2]="top + plotHeight" />
    </svg>

    <table class="visually-hidden">
      <caption>{{ label() }}</caption>
      <thead>
        <tr><th scope="col">Période</th><th scope="col">Réel</th>@if (showDemo()) {<th scope="col">Démo</th>}</tr>
      </thead>
      <tbody>
        @for (column of columns(); track $index) {
          <tr><th scope="row">{{ column.title }}</th><td>{{ column.real }}</td>@if (showDemo()) {<td>{{ column.demo }}</td>}</tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    :host {
      display: block;
    }

    .chart {
      display: block;
      width: 100%;
      height: auto;
      overflow: visible;
    }

    .chart__grid {
      stroke: var(--c-line);
      stroke-width: 1;
    }

    .chart__axis {
      stroke: var(--c-line-strong);
      stroke-width: 1;
    }

    .chart__tick,
    .chart__label {
      font-size: 11px;
      fill: var(--c-ink-faint);
      font-variant-numeric: tabular-nums;
    }

    .chart__hit {
      fill: transparent;
    }

    .chart__real {
      fill: var(--dash-real, var(--c-accent));
    }

    .chart__demo {
      fill: var(--dash-demo, #b9c2cf);
    }

    .chart__bar:hover .chart__hit {
      fill: color-mix(in srgb, var(--c-accent) 6%, transparent);
    }
  `,
})
export class ColumnChart {
  readonly columns = input.required<readonly Column[]>();
  readonly showDemo = input(false);
  readonly label = input('');

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly left = LEFT;
  protected readonly right = RIGHT;
  protected readonly top = TOP;
  protected readonly plotHeight = HEIGHT - TOP - BOTTOM;

  private readonly max = computed(() =>
    niceMax(Math.max(0, ...this.columns().map((column) => column.real + (this.showDemo() ? column.demo : 0)))),
  );

  protected readonly ticks = computed(() => {
    const max = this.max();
    return [0, 0.25, 0.5, 0.75, 1].map((share) => ({
      value: share * max,
      y: TOP + this.plotHeight * (1 - share),
      text: formatIntegerFr(Math.round(share * max)),
    }));
  });

  protected readonly bars = computed(() => {
    const columns = this.columns();
    const slot = (WIDTH - LEFT - RIGHT) / Math.max(columns.length, 1);
    const width = Math.max(2, Math.min(28, slot * 0.66));
    const every = Math.max(1, Math.ceil(columns.length / 8));
    const scale = this.plotHeight / this.max();
    return columns.map((column, index) => {
      const demo = this.showDemo() ? column.demo : 0;
      const realHeight = column.real * scale;
      const demoHeight = demo * scale;
      const base = TOP + this.plotHeight;
      const tooltip = this.showDemo() && column.demo > 0
        ? `${column.title} : ${formatIntegerFr(column.real)} réel, ${formatIntegerFr(column.demo)} démo`
        : `${column.title} : ${formatIntegerFr(column.real)}`;
      return {
        slot,
        slotX: LEFT + index * slot,
        x: LEFT + index * slot + (slot - width) / 2,
        width,
        realY: base - realHeight,
        realHeight,
        demoY: base - realHeight - demoHeight,
        demoHeight,
        label: column.label,
        showLabel: index % every === 0 || index === columns.length - 1 && (columns.length - 1) % every > every / 2,
        tooltip,
      };
    });
  });
}
