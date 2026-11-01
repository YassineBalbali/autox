import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Icon } from '../../shared/ui/icon';
import { Interpretation } from './smart-search';

/**
 * Above the filter chips, after a natural-language search: what the visitor wrote, and — in a
 * quieter line — what of it is not among the filters.
 *
 * <p>The chips below are the understood part, each removable like any other filter; this only
 * adds what chips cannot say. The second line matters most: "en bon état" not understood is a
 * request the results do not honour, and the visitor should not have to guess that.
 */
@Component({
  selector: 'app-interpretation-note',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="note">
      <p class="note__text">
        <app-icon class="note__icon" name="sparkles" [size]="16" />
        <span>
          <ng-container i18n="@@noteLead">Recherche intelligente :</ng-container>
          « {{ interpretation().text }} »
        </span>
        <button
          class="note__close"
          type="button"
          aria-label="Masquer ce rappel"
          i18n-aria-label="@@noteClose"
          (click)="dismissed.emit()"
        >
          <app-icon name="x" [size]="14" />
        </button>
      </p>
      @if (interpretation().notUnderstood.length > 0) {
        <p class="note__missed">
          <ng-container i18n="@@noteMissed">Non pris en compte :</ng-container>
          {{ interpretation().notUnderstood.join(', ') }}
        </p>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .note {
      margin-top: var(--s-2);
      font-size: var(--t-sm);
      color: var(--c-ink-soft);
    }

    .note__text {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      margin: 0;
    }

    .note__icon {
      flex: none;
      color: var(--c-accent);
    }

    .note__close {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 24px;
      height: 24px;
      padding: 0;
      background: none;
      border: 0;
      border-radius: var(--r-sm);
      color: var(--c-ink-faint);
      cursor: pointer;

      &:hover {
        color: var(--c-ink);
        background: var(--c-surface-sunk);
      }
    }

    .note__missed {
      margin: var(--s-0) 0 0;
      padding-left: calc(16px + var(--s-2));
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
    }
  `,
})
export class InterpretationNote {
  readonly interpretation = input.required<Interpretation>();
  readonly dismissed = output();
}
