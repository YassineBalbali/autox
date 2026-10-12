import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** What the message is: a failure, a confirmation, or neutral context. */
export type AlertKind = 'error' | 'success' | 'info';

/**
 * A short message about the form it sits above.
 *
 * <p>`role="alert"` on the error and success kinds, so a screen reader announces the
 * outcome of a submit the user cannot see. Not on `info`, which is static context present
 * when the page loads and would otherwise be read out of turn.
 *
 * <p>Colour is never the only signal: each kind carries its own text prefix for a user who
 * cannot distinguish the two, and the element is a landmark for anyone who cannot see it
 * at all.
 */
@Component({
  selector: 'app-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="alert" [class]="'alert--' + kind()" [attr.role]="role()">
      <strong class="alert__prefix">{{ prefix() }}</strong>
      <span><ng-content /></span>
    </div>
  `,
  styles: `
    .alert {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      padding: var(--s-3);
      border: 1px solid;
      border-radius: var(--r-md);
      font-size: var(--t-sm);
    }

    .alert__prefix {
      font-weight: var(--fw-bold);
    }

    .alert--error {
      background: var(--c-danger-soft);
      border-color: var(--c-danger);
      color: var(--c-danger);
    }

    .alert--success {
      background: var(--c-success-soft);
      border-color: var(--c-success);
      color: var(--c-success);
    }

    .alert--info {
      background: var(--c-accent-soft);
      border-color: var(--c-accent);
      color: var(--c-accent-strong);
    }
  `,
})
export class Alert {
  readonly kind = input<AlertKind>('error');

  readonly role = computed(() => (this.kind() === 'info' ? null : 'alert'));

  readonly prefix = computed(() => PREFIXES_FR[this.kind()]);
}

const PREFIXES_FR: Record<AlertKind, string> = {
  error: 'Erreur',
  success: 'C’est fait',
  info: 'À noter',
};
