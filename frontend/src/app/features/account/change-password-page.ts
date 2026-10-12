import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';

import { ProfileService } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { deviceLabel } from '../../core/auth/device-label';
import { TextField } from '../../shared/forms/text-field';
import {
  PASSWORD_MIN_LENGTH,
  matchesPassword,
  mirrorPassword,
  passwordValidators,
  requiredText,
} from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/**
 * Change the password of a signed-in account.
 *
 * <p><b>The response carries a new token pair, and adopting it is the whole of this
 * screen's difficulty.</b> The API revokes every refresh-token family on the account — the
 * point of the operation, since a password is changed precisely when someone else may be
 * holding a session — and then re-establishes one for the caller, returning it in the same
 * body. A screen that ignored that body would keep the tokens it already had, which the
 * server has just revoked; the user would appear to succeed, and then be signed out on the
 * next renewal. So the pair is handed straight to {@link AuthService.adoptAuthResponse},
 * and the user stays where they are.
 *
 * <p>The current password is required by the API and is not a formality: an access token
 * can be stolen, and without this check a stolen one would be enough to take the account
 * away from its owner permanently.
 */
@Component({
  selector: 'app-change-password-page',
  imports: [ReactiveFormsModule, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './change-password-page.html',
  styleUrl: './account.scss',
})
export class ChangePasswordPage {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);

  protected readonly passwordMinLength = PASSWORD_MIN_LENGTH;
  protected readonly changed = signal(false);

  protected readonly form = new FormGroup({
    currentPassword: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    newPassword: new FormControl('', { nonNullable: true, validators: passwordValidators }),
    newPasswordConfirmation: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), matchesPassword('newPassword')],
    }),
  });

  protected readonly state = submitState(this.form);

  constructor() {
    mirrorPassword(this.form.controls.newPassword, this.form.controls.newPasswordConfirmation);

    // The confirmation is cleared the moment the user starts typing again, so it cannot sit
    // above a second, unsubmitted change. Runs before the `changed.set(true)` below, since
    // `reset()` emits synchronously.
    this.form.valueChanges.subscribe(() => this.changed.set(false));
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    this.state.begin();

    this.api
      .changePassword({
        changePasswordRequest: {
          currentPassword: value.currentPassword,
          newPassword: value.newPassword,
          // The session this call establishes is a new one, so it needs its own label or it
          // appears unnamed on the sessions screen the user may well visit next.
          deviceLabel: deviceLabel(),
        },
      })
      .subscribe({
        next: (response) => {
          this.state.succeed();
          this.auth.adoptAuthResponse(response);
          // Cleared rather than left filled: three password fields holding real passwords,
          // on a screen the user has finished with, in a form a browser may offer to
          // restore.
          this.form.reset();
          this.changed.set(true);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }
}
