import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ResultsView } from './view-preference';

const OPTIONS: readonly { value: ResultsView; label: string }[] = [
  { value: 'liste', label: 'Liste' },
  { value: 'grille', label: 'Grille' },
];

/**
 * The list/grid switch in the results toolbar.
 *
 * <p>Two buttons that behave as one control: `aria-pressed` says which is on, and the
 * group's label says what they switch. Words rather than icons — "Liste" and "Grille"
 * need no legend, and two near-identical glyphs of lines and squares do.
 *
 * <p>Holds no state. The choice belongs to {@link ViewPreference}; this reports a click.
 */
@Component({
  selector: 'app-view-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="view-toggle" role="group" aria-label="Affichage des résultats" i18n-aria-label="@@viewLabel">
      @for (option of options; track option.value) {
        <button
          class="view-toggle__button"
          type="button"
          [class.view-toggle__button--on]="view() === option.value"
          [attr.aria-pressed]="view() === option.value"
          (click)="changed.emit(option.value)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styles: `
    // A segmented control: two buttons sharing one border, the chosen one in the accent's
    // active-filter treatment.
    .view-toggle {
      display: inline-flex;
    }

    .view-toggle__button {
      height: var(--h-control);
      padding: 0 var(--s-3);
      border: var(--border);
      background: var(--c-surface);
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);

      &:first-child {
        border-radius: var(--r-md) 0 0 var(--r-md);
      }

      &:last-child {
        // Shares its left edge with the first button rather than doubling the line.
        margin-left: -1px;
        border-radius: 0 var(--r-md) var(--r-md) 0;
      }

      &:hover {
        color: var(--c-ink);
      }
    }

    .view-toggle__button--on {
      // Above its neighbour, so the accent border is not half-covered by the shared edge.
      position: relative;
      z-index: 1;
      border-color: var(--c-accent);
      background: var(--c-accent-soft);
      color: var(--c-accent);

      &:hover {
        color: var(--c-accent);
      }
    }
  `,
})
export class ViewToggle {
  readonly view = input.required<ResultsView>();
  readonly changed = output<ResultsView>();

  protected readonly options = OPTIONS;
}
