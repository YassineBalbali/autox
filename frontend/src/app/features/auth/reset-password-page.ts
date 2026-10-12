import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AuthenticationService } from '../../core/api';
import { SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService, skipAuth } from '../../core/auth/auth.service';
import { FlashService } from '../../core/ui/flash.service';
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
 * Spend a reset link and choose a new password.
 *
 * <p>Reached from the emailed link, which carries the token as `?token=…`. Nothing on this
 * screen validates the token — only the API can, and asking it twice would spend a
 * single-use credential to find out whether it is spendable. So the form submits and the
 * failure, if there is one, is reported properly: the one error message that matters here
 * is "this link has expired or has already been used", and it needs the way to get a new
 * one next to it.
 */
@Component({
  selector: 'app-reset-password-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reset-password-page.html',
})
export class ResetPasswordPage {
  private readonly api = inject(AuthenticationService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly flash = inject(FlashService);

  /**
   * The token from the link, bound from the query string by `withComponentInputBinding`.
   *
   * <p>Never rendered. It is a bearer credential: on screen it is in a screenshot, in a
   * support ticket, and in whatever is recording the session.
   */
  readonly token = input('');

  protected readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  /** A link arrived at without a token — hand-typed, or truncated by a mail client. */
  protected readonly tokenMissing = computed(() => this.token().trim().length === 0);

  protected readonly form = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: passwordValidators }),
    passwordConfirmation: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), matchesPassword('password')],
    }),
  });

  protected readonly state = submitState(this.form);

  constructor() {
    mirrorPassword(this.form.controls.password, this.form.controls.passwordConfirmation);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.state.begin();

    this.api
      .resetPassword(
        {
          resetPasswordRequest: {
            token: this.token(),
            newPassword: this.form.getRawValue().password,
          },
        },
        'body',
        false,
        { context: skipAuth() },
      )
      .subscribe({
        next: () => {
          this.state.succeed();

          // The reset revokes every session on the account, so any tokens held here are
          // already dead. Clearing them locally means the user is signed out rather than
          // left holding credentials that will fail on the next request — which is also the
          // right outcome when the reset was done because the account was compromised.
          this.auth.clearSession();

          this.flash.set(
            'Votre mot de passe a été modifié. Connectez-vous avec votre nouveau mot de passe.',
          );
          void this.router.navigateByUrl(SIGN_IN_ROUTE);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }
}
