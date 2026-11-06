import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * Whether the demo seed is in the figures. A real switch, announced as one, and amber when on:
 * the figures it changes are not the site's own activity.
 */
@Component({
  selector: 'app-demo-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      role="switch"
      class="switch"
      [class.switch--on]="on()"
      [attr.aria-checked]="on()"
      [disabled]="disabled()"
      [attr.title]="disabled() ? 'Aucune donnée de démonstration sur ce site' : null"
      data-action="toggle-demo"
      (click)="toggled.emit()"
    >
      <span class="switch__track" aria-hidden="true"><span class="switch__thumb"></span></span>
      Données de démo
    </button>
  `,
  styles: `
    .switch {
      display: inline-flex;
      align-items: center;
      gap: var(--s-2);
      min-height: 36px;
      padding: 0 var(--s-1);
      border: 0;
      background: none;
      color: var(--c-ink);
      font: inherit;
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      cursor: pointer;

      &:disabled {
        color: var(--c-ink-faint);
        cursor: not-allowed;
      }
    }

    .switch__track {
      position: relative;
      width: 34px;
      height: 20px;
      border-radius: 999px;
      background: var(--c-line-strong);
      transition: background 0.15s;
    }

    .switch__thumb {
      position: absolute;
      top: 2px;
      left: 2px;
      width: 16px;
      height: 16px;
      border-radius: 50%;
      background: #ffffff;
      box-shadow: 0 1px 2px rgb(11 31 58 / 25%);
      transition: transform 0.15s;
    }

    .switch--on .switch__track {
      background: var(--c-warn);
    }

    .switch--on .switch__thumb {
      transform: translateX(14px);
    }
  `,
})
export class DemoSwitch {
  readonly on = input.required<boolean>();
  readonly disabled = input(false);
  readonly toggled = output<void>();
}
