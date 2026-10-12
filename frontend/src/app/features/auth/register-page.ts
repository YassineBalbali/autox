import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';

import { Country, ReferenceService, UserRole } from '../../core/api';
import { ACCOUNT_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { TextField } from '../../shared/forms/text-field';
import {
  MAX_LENGTHS,
  PASSWORD_MIN_LENGTH,
  emailValidators,
  matchesPassword,
  mirrorPassword,
  passwordValidators,
  phoneValidators,
  requiredText,
} from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/** The only market with content today, and the default so the select is never empty. */
const DEFAULT_COUNTRY = 'FR';

/**
 * Create an account, as a private seller or as a dealer.
 *
 * <p>The account type is the first question on the form rather than a toggle further down,
 * because it changes what the form asks for and a user should not discover two more
 * required fields after filling in the eight above them. Choosing "professionnel" adds the
 * company name and the registry number — both captured here rather than left to a later
 * profile edit, since a dealer account with neither is one nobody can verify while its
 * listings are already public.
 */
@Component({
  selector: 'app-register-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './register-page.html',
  styleUrl: './register-page.scss',
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly roles = UserRole;
  protected readonly maxLengths = MAX_LENGTHS;
  protected readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  /**
   * The enabled markets, for the country field.
   *
   * <p>Asked of the API rather than hard-coded to France. The country decides the currency
   * and the distance unit on every listing the account will ever create, and it is written
   * once at registration — guessing it in the client is the kind of shortcut that is
   * invisible until the second market opens and every account in it says `FR`.
   *
   * <p>A failure falls back to France alone rather than blocking registration: the
   * reference list is a convenience, and the API validates the code either way.
   */
  private readonly countries = toSignal(
    inject(ReferenceService)
      .countries()
      .pipe(catchError(() => of([{ code: DEFAULT_COUNTRY, name: 'France' } as Country]))),
    { initialValue: [] as Country[] },
  );

  protected readonly countryOptions = computed(() =>
    this.countries().filter((country): country is Country & { code: string } => !!country.code),
  );

  protected readonly form = new FormGroup({
    role: new FormControl<UserRole>(UserRole.PARTICULIER, { nonNullable: true }),
    email: new FormControl('', { nonNullable: true, validators: emailValidators }),
    password: new FormControl('', { nonNullable: true, validators: passwordValidators }),
    passwordConfirmation: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), matchesPassword('password')],
    }),
    displayName: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), Validators.maxLength(MAX_LENGTHS.displayName)],
    }),
    firstName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.firstName)],
    }),
    lastName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.lastName)],
    }),
    phone: new FormControl('', { nonNullable: true, validators: phoneValidators }),
    countryCode: new FormControl(DEFAULT_COUNTRY, {
      nonNullable: true,
      validators: [requiredText()],
    }),
    companyName: new FormControl('', { nonNullable: true }),
    registrationNumber: new FormControl('', { nonNullable: true }),
  });

  protected readonly state = submitState(this.form);

  /**
   * Mirrors the role control into a signal, because the template branches on it.
   *
   * <p>A `FormControl` is not signal-backed, so reading `form.controls.role.value` from a
   * template under zoneless change detection renders the initial value and never updates —
   * the professional fields would appear only if something else happened to trigger a
   * check. Driven from `valueChanges`, which is also where the validators are swapped, so
   * the two can never disagree about which role is selected.
   */
  protected readonly role = signal<UserRole>(UserRole.PARTICULIER);

  protected readonly isProfessional = computed(() => this.role() === UserRole.PROFESSIONNEL);

  constructor() {
    this.form.controls.role.valueChanges.subscribe((role) => this.applyRole(role));
    mirrorPassword(this.form.controls.password, this.form.controls.passwordConfirmation);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const professional = value.role === UserRole.PROFESSIONNEL;

    this.state.begin();

    this.auth
      .register({
        email: value.email.trim(),
        password: value.password,
        role: value.role,
        displayName: value.displayName.trim(),
        // Blank optional fields are sent as undefined rather than as "": the API reads a
        // blank string on a profile field as "clear this", and sending one here would be a
        // cleared field on an account that never had a value to clear.
        firstName: blankToUndefined(value.firstName),
        lastName: blankToUndefined(value.lastName),
        phone: blankToUndefined(value.phone),
        countryCode: value.countryCode,
        companyName: professional ? value.companyName.trim() : undefined,
        registrationNumber: professional ? value.registrationNumber.trim() : undefined,
      })
      .subscribe({
        next: () => {
          this.state.succeed();
          // Registration returns a token pair, so the account is already signed in. Sending
          // the user to a sign-in form they have no reason to fill in again would be the
          // obvious mistake here.
          void this.router.navigateByUrl(ACCOUNT_ROUTE);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }

  /**
   * Adds or removes the dealer rules as the role changes.
   *
   * <p>Not one validator inspecting a sibling control: these two fields have to be *valid*
   * for a private seller, not merely ignored, or the form can never be submitted. And their
   * touched state has to be cleared on the way out, or choosing "professionnel" and
   * changing back leaves two fields marked in red that the user can no longer see or fix.
   */
  private applyRole(role: UserRole): void {
    this.role.set(role);
    const professional = role === UserRole.PROFESSIONNEL;

    const rules: Record<string, ValidatorFn[]> = {
      companyName: [requiredText(), Validators.maxLength(MAX_LENGTHS.companyName)],
      registrationNumber: [
        requiredText(),
        Validators.maxLength(MAX_LENGTHS.registrationNumber),
      ],
    };

    for (const name of ['companyName', 'registrationNumber'] as const) {
      const control = this.form.controls[name];
      if (professional) {
        control.setValidators(rules[name]);
      } else {
        control.clearValidators();
        control.markAsUntouched();
      }
      // emitEvent false: this runs from a valueChanges handler, and re-emitting would put
      // the form's own status change back through the same subscription.
      control.updateValueAndValidity({ emitEvent: false });
    }
  }
}

function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
