import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { switchMap } from 'rxjs';

import { firstErrorFr } from './validation';

let sequence = 0;

/** The input types this field renders. `textarea` swaps the element, not the behaviour. */
export type TextFieldType = 'text' | 'email' | 'password' | 'tel' | 'url' | 'textarea';

/**
 * A labelled text input with its validation message, as one component.
 *
 * <p>It renders the control itself rather than projecting one, which is the decision worth
 * explaining. Content projection would be more flexible and would also mean every form
 * wiring `for`/`id`, `aria-describedby` and `aria-invalid` by hand on every field — around
 * thirty fields across these screens, each one a chance to forget the `aria-describedby`
 * and leave a screen reader announcing a field as invalid without saying why. Owning the
 * markup makes that correct once. The cost is that a control this does not cover — a
 * select, a radio group — is written out longhand in the screen that needs it, and two of
 * them are.
 *
 * <p>The error appears on `touched` rather than on every keystroke, so a user is not told
 * their email is invalid while they are still typing the first letter of it. Submitting
 * marks every control touched, which is what reveals the untouched empty ones.
 */
@Component({
  selector: 'app-text-field',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label class="field__label" [attr.for]="id">
        {{ label() }}
        @if (optional()) {
          <span class="field__optional" i18n="@@fieldOptional">(optionnel)</span>
        }
      </label>

      @if (type() === 'textarea') {
        <textarea
          class="input"
          [id]="id"
          [formControl]="control()"
          [attr.rows]="rows()"
          [attr.maxlength]="maxLength()"
          [attr.aria-invalid]="showsError()"
          [attr.aria-describedby]="describedBy()"
        ></textarea>
      } @else {
        <input
          class="input"
          [id]="id"
          [type]="type()"
          [formControl]="control()"
          [attr.autocomplete]="autocomplete()"
          [attr.inputmode]="inputMode()"
          [attr.maxlength]="maxLength()"
          [attr.aria-invalid]="showsError()"
          [attr.aria-describedby]="describedBy()"
        />
      }

      @if (hint()) {
        <p class="field__hint" [id]="hintId">{{ hint() }}</p>
      }

      <!-- aria-live rather than role="alert": the message is attached to the field through
           aria-describedby and is read when focus lands there, so announcing it a second
           time the moment it appears would interrupt a user mid-form. "polite" waits for a
           pause. -->
      @if (showsError()) {
        <p class="field__error" [id]="errorId" aria-live="polite">{{ errorText() }}</p>
      }
    </div>
  `,
  styles: '',
})
export class TextField {
  /** The control. Owned by the parent form, so this component holds no state of its own. */
  readonly control = input.required<FormControl<string>>();
  readonly label = input.required<string>();
  readonly type = input<TextFieldType>('text');

  /**
   * The browser autofill hint, and not optional in practice: without `current-password` or
   * `new-password` a password manager cannot tell a sign-in field from a change-password
   * one, and will offer to overwrite a saved entry on the wrong form.
   */
  readonly autocomplete = input<string | null>(null);
  readonly inputMode = input<string | null>(null);
  readonly maxLength = input<number | null>(null);
  readonly rows = input<number | null>(null);
  readonly hint = input<string | null>(null);

  /** Marks the label, for forms where most fields are required and few are not. */
  readonly optional = input(false);

  /**
   * Set when the API named this field in a validation failure.
   *
   * <p>It carries no reason, and cannot: the API's per-field text is untranslated developer
   * wording that is explicitly not a contract. So this renders "refused" and leaves the
   * explaining to the client-side rules above, which is the whole argument for having
   * them. A machine-readable `constraint` per violation would close the gap; it is not in
   * the contract today.
   */
  readonly rejectedByServer = input(false);

  private readonly uid = ++sequence;
  readonly id = `champ-${this.uid}`;
  readonly hintId = `${this.id}-aide`;
  readonly errorId = `${this.id}-erreur`;

  /**
   * Re-reads the control whenever it changes in any way.
   *
   * <p>Needed because `AbstractControl` is not signal-backed: `control.touched` and
   * `control.errors` are plain properties, so a `computed` over them would be evaluated
   * once and never invalidated. Under zoneless change detection that is not a subtle
   * staleness — nothing re-runs at all unless a signal it read has changed, so the
   * validation message would simply never appear.
   *
   * <p>`control.events` emits on value, status, touched and pristine transitions, which is
   * exactly the set this component renders from. Flattened through `toObservable` rather
   * than subscribed directly so that a parent swapping the control is handled too.
   */
  private readonly controlEvents = toSignal(
    toObservable(this.control).pipe(switchMap((control) => control.events)),
    { initialValue: null },
  );

  readonly showsError = computed(() => this.errorText() !== null);

  readonly errorText = computed(() => {
    this.controlEvents();
    const control = this.control();
    // Touched, not dirty: an empty required field the user skipped past has been touched
    // and never dirtied, and it is the one most worth flagging.
    if (control.invalid && control.touched) {
      return firstErrorFr(control.errors);
    }
    if (this.rejectedByServer()) {
      return 'Cette valeur a été refusée. Corrigez-la et réessayez.';
    }
    return null;
  });

  readonly describedBy = computed(() => {
    const ids: string[] = [];
    if (this.hint()) {
      ids.push(this.hintId);
    }
    if (this.showsError()) {
      ids.push(this.errorId);
    }
    return ids.length > 0 ? ids.join(' ') : null;
  });
}
