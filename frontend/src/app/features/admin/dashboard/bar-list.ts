import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatIntegerFr } from '../../../shared/format/number';

export interface BarItem {
  readonly label: string;
  readonly value: number;
  /** Shown after the figure: a share, a change. */
  readonly note?: string;
  /** Of {@link value}, the demo seed's part, drawn in grey. */
  readonly demo?: number;
}

/** A ranked list as horizontal bars, each against the largest. */
@Component({
  selector: 'app-bar-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="bars" role="list">
      @for (item of rows(); track item.label) {
        <li class="bars__row">
          <span class="bars__label" [attr.title]="item.label">{{ item.label }}</span>
          <span class="bars__track" aria-hidden="true">
            <span class="bars__fill" [style.width.%]="item.realWidth"></span>
            @if (item.demoWidth > 0) {
              <span class="bars__fill bars__fill--demo" [style.width.%]="item.demoWidth"></span>
            }
          </span>
          <span class="bars__value">
            {{ number(item.value) }}
            @if (item.note) {
              <span class="bars__note">{{ item.note }}</span>
            }
          </span>
        </li>
      }
    </ol>
  `,
  styles: `
    .bars {
      display: grid;
      gap: var(--s-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .bars__row {
      display: grid;
      grid-template-columns: minmax(6rem, 9rem) 1fr auto;
      align-items: center;
      gap: var(--s-2);
      font-size: var(--t-sm);
    }

    .bars__label {
      overflow: hidden;
      color: var(--c-ink);
      white-space: nowrap;
      text-overflow: ellipsis;
    }

    .bars__track {
      display: flex;
      height: 8px;
      overflow: hidden;
      border-radius: 999px;
      background: var(--c-surface-sunk);
    }

    .bars__fill {
      height: 100%;
      background: var(--dash-real, var(--c-accent));
    }

    .bars__fill--demo {
      background: var(--dash-demo, #b9c2cf);
    }

    .bars__value {
      min-width: 3rem;
      font-weight: var(--fw-semibold);
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    .bars__note {
      margin-left: var(--s-1);
      font-weight: var(--fw-regular);
      color: var(--c-ink-faint);
    }
  `,
})
export class BarList {
  readonly items = input.required<readonly BarItem[]>();

  protected readonly number = formatIntegerFr;

  protected readonly rows = computed(() => {
    const max = Math.max(1, ...this.items().map((item) => item.value));
    return this.items().map((item) => {
      const demo = Math.min(item.demo ?? 0, item.value);
      return {
        ...item,
        realWidth: ((item.value - demo) / max) * 100,
        demoWidth: (demo / max) * 100,
      };
    });
  });
}
