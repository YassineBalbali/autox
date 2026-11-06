import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { formatIntegerFr } from '../../../shared/format/number';

export interface VerdictPart {
  readonly key: 'good' | 'market' | 'above' | 'none';
  readonly label: string;
  readonly value: number;
  /** "33,3 %". */
  readonly text: string;
}

/** Published listings by price indicator, as one bar of their shares, and the figures under it. */
@Component({
  selector: 'app-verdict-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar" role="img" aria-label="Annonces en ligne par indicateur de prix">
      @for (part of parts(); track part.key) {
        @if (part.value > 0) {
          <span [class]="'bar__part tone--' + part.key" [style.flex-grow]="part.value" [attr.title]="part.label + ' : ' + part.text"></span>
        }
      }
    </div>
    <ul class="legend" role="list">
      @for (part of parts(); track part.key) {
        <li [attr.data-verdict]="part.key">
          <span [class]="'legend__swatch tone--' + part.key" aria-hidden="true"></span>
          <span>{{ part.label }}</span>
          <strong>{{ number(part.value) }}</strong>
          <span class="legend__share">{{ part.text }}</span>
        </li>
      }
    </ul>
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--s-3);
    }

    .bar {
      display: flex;
      gap: 2px;
      height: 14px;
      overflow: hidden;
      border-radius: 999px;
    }

    .bar__part {
      min-width: 4px;
    }

    .tone--good {
      background: #177245;
    }

    .tone--market {
      background: var(--c-accent);
    }

    .tone--above {
      background: #c97a08;
    }

    .tone--none {
      background: #c8cfd9;
    }

    .legend {
      display: grid;
      gap: var(--s-1);
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--t-sm);

      li {
        display: grid;
        grid-template-columns: 10px 1fr auto 3.5rem;
        align-items: center;
        gap: var(--s-2);
      }
    }

    .legend__swatch {
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }

    .legend__share {
      color: var(--c-ink-faint);
      text-align: right;
    }
  `,
})
export class VerdictBar {
  readonly parts = input.required<readonly VerdictPart[]>();

  protected readonly number = formatIntegerFr;
}
