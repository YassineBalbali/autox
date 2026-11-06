import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Icon } from '../../../shared/ui/icon';
import { IconName } from '../../../shared/ui/icons.data';

/** How much something needs a moderator: `ok` when nothing does. */
export type AttentionTone = 'ok' | 'warn' | 'danger';

/**
 * Something waiting for a moderator, at the top of the dashboard: the figure, how long it has
 * waited, and the way to deal with it. Coloured by how much it needs attention, with an icon and
 * words that say the same, so the colour is never the only signal.
 */
@Component({
  selector: 'app-attention-card',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': "'attention attention--' + tone()" },
  template: `
    <span class="attention__icon"><app-icon [name]="tone() === 'ok' ? 'circle-check' : icon()" [size]="22" /></span>
    <div>
      <h2 class="attention__title">{{ title() }}</h2>
      <p class="attention__figure"><ng-content /></p>
      @if (since()) {
        <p class="attention__since">{{ since() }}</p>
      }
    </div>
    <a class="btn btn--sm attention__action" [routerLink]="link()">{{ action() }}</a>
  `,
  styles: `
    :host {
      display: grid;
      grid-template-columns: auto 1fr;
      align-items: center;
      gap: var(--s-2) var(--s-3);
      padding: var(--s-3) var(--s-4);
      border: 1px solid var(--c-line-strong);
      border-left: 4px solid var(--tone);
      border-radius: var(--r-lg);
      background: var(--c-surface);

      @media (min-width: 480px) {
        grid-template-columns: auto 1fr auto;
      }
    }

    :host(.attention--ok) {
      --tone: var(--c-success);
      --tone-soft: var(--c-success-soft);
    }

    :host(.attention--warn) {
      --tone: var(--c-warn);
      --tone-soft: var(--c-warn-soft);
    }

    :host(.attention--danger) {
      --tone: var(--c-danger);
      --tone-soft: var(--c-danger-soft);
    }

    .attention__icon {
      display: grid;
      place-items: center;
      width: 44px;
      height: 44px;
      border-radius: 50%;
      background: var(--tone-soft);
      color: var(--tone);
    }

    .attention__title {
      margin: 0;
      font-size: var(--t-xs);
      font-weight: var(--fw-bold);
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--c-ink-soft);
    }

    .attention__figure {
      margin: 2px 0 0;
      font-size: var(--t-md);

      ::ng-deep strong {
        font-size: var(--t-xl);
        font-variant-numeric: tabular-nums;
      }
    }

    :host(.attention--ok) .attention__figure {
      color: var(--c-ink-soft);
    }

    .attention__since {
      margin: 0;
      font-size: var(--t-xs);
      color: var(--c-ink-soft);
    }

    .attention__action {
      grid-column: 1 / -1;
      justify-self: start;
      border-color: var(--tone);
      background: var(--tone);
      color: #ffffff;

      @media (min-width: 480px) {
        grid-column: auto;
      }
    }

    :host(.attention--ok) .attention__action {
      border-color: var(--c-line-strong);
      background: var(--c-surface);
      color: var(--c-ink);
    }
  `,
})
export class AttentionCard {
  readonly tone = input.required<AttentionTone>();
  readonly icon = input.required<IconName>();
  readonly title = input.required<string>();
  /** "La plus ancienne attend depuis 3 j", or nothing. */
  readonly since = input<string | null>(null);
  readonly action = input.required<string>();
  readonly link = input.required<string>();
}
