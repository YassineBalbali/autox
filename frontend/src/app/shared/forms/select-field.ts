import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { switchMap } from 'rxjs';

import { firstErrorFr } from './validation';

let sequence = 0;

export interface SelectOption {
  readonly value: string;
  readonly label: string;
}

/**
 * A labelled `<select>` with its validation message — the counterpart of `TextField`.
 *
 * <p>Same reasons, same shape: it owns the markup so that `for`/`id`, `aria-invalid` and
 * `aria-describedby` are wired once and correctly, instead of by hand on every dropdown
 * of a form that has seven of them.
 *
 * <p>The empty option is the placeholder and is always present, so a select can be put
 * back to "nothing chosen" — and a required one then fails validation visibly, rather
 * than silently submitting whatever happened to be first in the list.
 */
@Component({
  selector: 'app-select-field',
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

      <select
        class="input"
        [id]="id"
        [formControl]="control()"
        [attr.aria-invalid]="showsError()"
        [attr.aria-describedby]="describedBy()"
      >
        <option value="">{{ placeholder() }}</option>
        @for (option of options(); track option.value) {
          <option [value]="option.value">{{ option.label }}</option>
        }
      </select>

      @if (hint()) {
        <p class="field__hint" [id]="hintId">{{ hint() }}</p>
      }
      @if (showsError()) {
        <p class="field__error" [id]="errorId" aria-live="polite">{{ errorText() }}</p>
      }
    </div>
  `,
})
export class SelectField {
  readonly control = input.required<FormControl<string>>();
  readonly label = input.required<string>();
  readonly options = input.required<readonly SelectOption[]>();
  readonly placeholder = input('Choisir…');
  readonly hint = input<string | null>(null);
  readonly optional = input(false);

  private readonly uid = ++sequence;
  readonly id = `liste-${this.uid}`;
  readonly hintId = `${this.id}-aide`;
  readonly errorId = `${this.id}-erreur`;

  /** `AbstractControl` is not signal-backed; see the same bridge in `TextField`. */
  private readonly controlEvents = toSignal(
    toObservable(this.control).pipe(switchMap((control) => control.events)),
    { initialValue: null },
  );

  readonly errorText = computed(() => {
    this.controlEvents();
    const control = this.control();
    return control.invalid && control.touched ? firstErrorFr(control.errors) : null;
  });

  readonly showsError = computed(() => this.errorText() !== null);

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
