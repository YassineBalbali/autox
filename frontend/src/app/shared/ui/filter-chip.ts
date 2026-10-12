import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * A filter that is either on or off: "Diesel", "Boîte automatique", "Professionnel".
 *
 * <p>The active state is the accent — outline, tint and text — which is the third and
 * last place the logo's blue is used, after prices and the primary button. Three uses is
 * the budget: a colour that marks what matters stops doing so the moment it is also a
 * decoration.
 *
 * <p>A real `<button>` with `aria-pressed`, so it is reachable by keyboard and announced
 * as a toggle with its state. It holds no state of its own: the caller owns which filters
 * are active, because that state belongs in the URL.
 */
@Component({
  selector: 'app-filter-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      class="chip"
      type="button"
      [class.chip--active]="active()"
      [attr.aria-pressed]="active()"
      (click)="toggled.emit(!active())"
    >
      <ng-content />
      @if (count() !== null) {
        <span class="chip__count">{{ count() }}</span>
      }
    </button>
  `,
  styles: `
    :host {
      display: inline-block;
    }

    .chip {
      display: inline-flex;
      align-items: center;
      gap: var(--s-1);
      height: calc(var(--h-control) - 6px);
      padding: 0 var(--s-2);
      border: 1px solid var(--c-line-strong);
      border-radius: var(--r-sm);
      background: var(--c-surface);
      color: var(--c-ink);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      white-space: nowrap;

      &:hover {
        border-color: var(--c-ink-faint);
      }
    }

    .chip--active {
      border-color: var(--c-accent);
      background: var(--c-accent-soft);
      color: var(--c-accent);

      &:hover {
        border-color: var(--c-accent-strong);
      }
    }

    // How many results the filter would give. Lighter than the label, in both states.
    .chip__count {
      font-weight: var(--fw-regular);
      font-variant-numeric: tabular-nums;
      opacity: 0.75;
    }
  `,
})
export class FilterChip {
  readonly active = input(false);
  readonly count = input<number | null>(null);

  /** Emits the state the chip is asking to move to. */
  readonly toggled = output<boolean>();
}
