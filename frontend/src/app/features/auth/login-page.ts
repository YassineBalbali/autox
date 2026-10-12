import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { RETURN_URL_PARAM, safeReturnUrl } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { FlashService } from '../../core/ui/flash.service';
import { TextField } from '../../shared/forms/text-field';
import { emailValidators, MAX_LENGTHS, requiredText } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/**
 * Sign in.
 *
 * <p>The password field carries no length rule, which is the one difference from every
 * other password field in the application. A minimum here would reject an existing
 * password shorter than today's rule — accounts predate rules — and, worse, it would tell
 * somebody probing the form what the rule is before they have an account. Sign-in checks
 * that a value is present and lets the API be the authority on whether it is right.
 */
@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login-page.html',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /**
   * Where to go afterwards, resolved once at construction.
   *
   * <p>Read from the URL rather than taken as a route input so it can go through
   * {@link safeReturnUrl} before anything navigates to it: the raw value is attacker-chosen
   * and an absolute URL in it turns this page into a phishing hop.
   */
  private readonly returnUrl = safeReturnUrl(
    this.router.parseUrl(this.router.url).queryParams[RETURN_URL_PARAM],
  );

  /** A message from the screen that sent the user here — a completed password reset. */
  protected readonly notice = signal(inject(FlashService).take());

  protected readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: emailValidators }),
    password: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
  });

  protected readonly state = submitState(this.form);
  protected readonly maxLengths = MAX_LENGTHS;

  protected submit(): void {
    if (this.form.invalid) {
      // Reveals the errors on fields the user never touched, which on an empty form is all
      // of them. Without this a submit of an empty form appears to do nothing at all.
      this.form.markAllAsTouched();
      return;
    }

    const { email, password } = this.form.getRawValue();
    this.state.begin();

    this.auth.login(email.trim(), password).subscribe({
      next: () => {
        this.state.succeed();
        void this.router.navigateByUrl(this.returnUrl);
      },
      error: (error: unknown) => this.state.fail(error),
    });
  }
}
