import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';

/**
 * Client-side validation rules, and the French sentence for each failure.
 *
 * <p><b>This file is load-bearing, not a convenience.</b> The API reports a rejected field
 * as `{field, message}` where `message` is untranslated developer text whose wording is
 * explicitly not a contract — so the client can neither show it nor match on it, and a
 * server-side rejection can only ever say "this field is wrong". The only place a user can
 * be told *why* is here, before the request is sent. See `fieldLabelFr` for the full note.
 *
 * <p>Which makes these rules a mirror of the server's, and mirrors drift. The constraints
 * are therefore written once, as named constants with the DTO they come from, so a change
 * on the backend has one place to land rather than six form definitions to hunt through.
 */

/**
 * Password length, from `RegisterRequest.password`: `@Size(min = 12, max = 128)`.
 *
 * <p>Length is the server's only rule — no composition requirement, deliberately — so it is
 * the client's only rule too. A form that demanded a digit and a symbol would be rejecting
 * passwords the API accepts, which is worse than useless: it is a rule the user cannot
 * discover from anywhere else.
 */
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_LENGTH = 128;

/** `@Size(max = ...)` on the registration and profile DTOs. */
export const MAX_LENGTHS = {
  email: 255,
  displayName: 120,
  firstName: 80,
  lastName: 80,
  companyName: 160,
  registrationNumber: 40,
  vatNumber: 40,
  legalForm: 60,
  websiteUrl: 255,
  description: 5000,
  addressLine: 160,
  deviceLabel: 120,
} as const;

/**
 * Phone shape, from `@Pattern("^\+[1-9][0-9]{6,18}$")`.
 *
 * <p>International format only, which is the API's rule rather than a French-market one: a
 * number stored as `0612345678` is ambiguous the moment a second country is enabled. The
 * hint on the field says so, because a French user typing their own number will reach for
 * the leading zero first.
 */
export const PHONE_PATTERN = /^\+[1-9][0-9]{6,18}$/;

/** A field that is required, but only once trimmed — " " is not a display name. */
export function requiredText(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    return typeof value === 'string' && value.trim().length > 0 ? null : { required: true };
  };
}

export const emailValidators: ValidatorFn[] = [
  requiredText(),
  // Angular's own email check, which is permissive by design. Deliberately not tightened:
  // a stricter regex rejects addresses that are legal and deliverable, and the only
  // authority on whether an address works is whether the mail arrives.
  Validators.email,
  Validators.maxLength(MAX_LENGTHS.email),
];

export const passwordValidators: ValidatorFn[] = [
  requiredText(),
  Validators.minLength(PASSWORD_MIN_LENGTH),
  Validators.maxLength(PASSWORD_MAX_LENGTH),
];

export const phoneValidators: ValidatorFn[] = [Validators.pattern(PHONE_PATTERN)];

/**
 * Cross-checks a confirmation field against the password, and puts the error on the
 * confirmation control rather than on the group.
 *
 * <p>On the control on purpose: an error on the group has nowhere to render next to a
 * field, so it ends up as a banner at the top of the form — which on a phone is above the
 * fold the user is looking at. Attached to the field that is wrong, it appears where they
 * are typing.
 */
export function matchesPassword(passwordKey: string): ValidatorFn {
  return (confirmation: AbstractControl): ValidationErrors | null => {
    const password = confirmation.parent?.get(passwordKey);
    if (!password || !confirmation.value) {
      return null;
    }
    return password.value === confirmation.value ? null : { passwordMismatch: true };
  };
}

/**
 * Wires a confirmation field to the password it mirrors. Call it once, after the group
 * exists; {@link matchesPassword} alone is not enough and silently so.
 *
 * <p>Two separate problems, both of which leave the form thinking it is valid when it is
 * not:
 *
 * <ul>
 *   <li><b>A control validates at construction, before it is attached to its group.</b> At
 *       that moment `confirmation.parent` is undefined, so the validator cannot find the
 *       password and returns null — and nothing re-runs it, because building a
 *       `FormGroup` does not revalidate the children handed to it.</li>
 *   <li><b>Angular re-runs a control's validators when <em>that</em> control changes, not
 *       when a sibling does.</b> So a user who types a password, confirms it, then goes
 *       back and edits the password leaves a confirmation that was checked against the old
 *       value and still reports itself valid. The form submits two different passwords and
 *       the API accepts the first one — a password the user does not think they chose.</li>
 * </ul>
 *
 * <p>No unsubscribe: the subscription is from one control to another inside the same form,
 * both owned by the component that created them, so it becomes unreachable with the
 * component rather than outliving it.
 */
export function mirrorPassword(
  password: AbstractControl,
  confirmation: AbstractControl,
): void {
  // Once now, because the validator's first run happened before the group existed.
  confirmation.updateValueAndValidity({ emitEvent: false });

  password.valueChanges.subscribe(() => {
    // Bubbles to the group, which is the point: a new error on the confirmation has to
    // make the form itself invalid, or the submit button stays enabled. `emitEvent: false`
    // keeps it from re-emitting `valueChanges` — the form's own subscribers would
    // otherwise see an edit on every keystroke in the password field.
    confirmation.updateValueAndValidity({ emitEvent: false });
  });
}

/**
 * A whole number within bounds, typed into a text field.
 *
 * <p>For years, mileages and prices. A text field with `inputmode="numeric"` rather than
 * `type="number"`: the number input accepts "1e5", swallows a comma silently in some
 * locales, and changes value when the wheel scrolls over it — on a price field, that is a
 * listing published at the wrong price.
 *
 * <p>Empty is valid here; pair it with {@link requiredText} when the field is required, so
 * that "missing" and "not a number" are two different messages.
 */
export function wholeNumber(min: number, max: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = typeof control.value === 'string' ? control.value.trim() : '';
    if (raw === '') {
      return null;
    }
    // Spaces are allowed and ignored: people type "15 000".
    const digits = raw.replace(/[\s\u00a0\u202f]/g, '');
    if (!/^\d{1,10}$/.test(digits)) {
      return { wholeNumber: true };
    }
    const value = Number(digits);
    return value < min || value > max ? { range: { min, max } } : null;
  };
}

/** The number a {@link wholeNumber} field holds, or `null` when it is empty or invalid. */
export function parseWholeNumber(raw: string | null | undefined): number | null {
  const digits = (raw ?? '').replace(/[\s\u00a0\u202f]/g, '');
  return /^\d{1,10}$/.test(digits) ? Number(digits) : null;
}

/**
 * The French sentence for the first failure on a control.
 *
 * <p>The first and not all of them: a field showing three sentences at once is a field
 * nobody reads, and the rules are ordered so that the first failure is the most useful one
 * to fix. Required beats too-short beats malformed.
 */
export function firstErrorFr(errors: ValidationErrors | null | undefined): string | null {
  if (!errors) {
    return null;
  }
  for (const [key, detail] of Object.entries(errors)) {
    const message = MESSAGES_FR[key];
    if (message) {
      return message(detail);
    }
  }
  // An unmapped validator. The generic sentence rather than nothing: a field marked
  // invalid with no explanation is a dead end for the user.
  return 'Cette valeur n’est pas valide.';
}

/**
 * Validator key to French text.
 *
 * <p>Keyed on Angular's own error keys, so `minlength` and `maxlength` arrive lowercase and
 * carry `{requiredLength, actualLength}` — the length is read from the error rather than
 * from a constant, so one message serves every field that uses the rule.
 */
const MESSAGES_FR: Record<string, (detail: unknown) => string> = {
  required: () => 'Ce champ est obligatoire.',
  email: () => 'Saisissez une adresse e-mail valide, par exemple nom@exemple.fr.',
  minlength: (detail) => `Au moins ${lengthOf(detail)} caractères.`,
  maxlength: (detail) => `${lengthOf(detail)} caractères au maximum.`,
  // One message for every pattern in the application would be meaningless, and there is
  // exactly one pattern in these forms. When a second arrives it needs its own validator
  // with its own key rather than a second meaning for this one.
  pattern: () => 'Utilisez le format international, par exemple +33612345678.',
  passwordMismatch: () => 'Les deux mots de passe ne correspondent pas.',
  wholeNumber: () => 'Saisissez un nombre entier, sans décimale.',
  range: (detail) => {
    const { min, max } = (detail ?? {}) as { min?: number; max?: number };
    return `Saisissez une valeur entre ${GROUPED.format(min ?? 0)} et ${GROUPED.format(max ?? 0)}.`;
  },
};

/** Bounds are shown grouped — "3 000 000", not "3000000" — with a space every font draws. */
const GROUPED = {
  format: (value: number) => new Intl.NumberFormat('fr-FR').format(value).replace(/\u202f/g, '\u00a0'),
};

function lengthOf(detail: unknown): number {
  const required = (detail as { requiredLength?: unknown } | null)?.requiredLength;
  return typeof required === 'number' ? required : 0;
}
