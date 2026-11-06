import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const WIDTH = 120;
const HEIGHT = 32;

/** A figure's shape over the period, under it: no axis, no numbers, only whether it moved. */
@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (points(); as line) {
      <svg class="spark" [attr.viewBox]="'0 0 ' + width + ' ' + height" preserveAspectRatio="none" aria-hidden="true">
        <polygon class="spark__area" [attr.points]="line.area" />
        <polyline class="spark__line" [attr.points]="line.path" />
      </svg>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .spark {
      display: block;
      width: 100%;
      height: 32px;
    }

    .spark__line {
      fill: none;
      stroke: var(--spark, var(--c-accent));
      stroke-width: 1.75;
      stroke-linejoin: round;
      vector-effect: non-scaling-stroke;
    }

    .spark__area {
      fill: color-mix(in srgb, var(--spark, var(--c-accent)) 12%, transparent);
    }
  `,
})
export class Sparkline {
  readonly values = input.required<readonly number[]>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;

  protected readonly points = computed(() => {
    const values = this.values();
    // A line along the bottom would read as data; nothing happened, so nothing is drawn.
    if (values.length < 2 || values.every((value) => value === 0)) {
      return null;
    }
    const max = Math.max(...values);
    const step = WIDTH / (values.length - 1);
    const coordinates = values.map((value, index) => `${(index * step).toFixed(1)},${(HEIGHT - 2 - (value / max) * (HEIGHT - 4)).toFixed(1)}`);
    return {
      path: coordinates.join(' '),
      area: `0,${HEIGHT} ${coordinates.join(' ')} ${WIDTH},${HEIGHT}`,
    };
  });
}
