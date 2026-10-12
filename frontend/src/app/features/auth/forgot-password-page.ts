import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthenticationService } from '../../core/api';
import { skipAuth } from '../../core/auth/auth.service';
import { TextField } from '../../shared/forms/text-field';
import { MAX_LENGTHS, emailValidators } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/**
 * Ask for a reset link.
 *
 * <p>The confirmation says the same thing whether or not the address has an account, which
 * is the API's design and has to be honoured here or it is pointless: an endpoint that
 * answers identically while the screen above it says "aucun compte avec cette adresse"
 * turns the form into a way to test whether somebody is a customer. So there is one
 * outcome — "if this address has an account, a link is on its way" — and the screen cannot
 * tell the difference either.
 *
 * <p>Which makes the wording the whole of the work on this screen. It has to be truthful
 * without being evasive, and it has to say what to do if nothing arrives, because the user
 * who mistyped their address gets exactly the same message as the user who did not.
 */
@Component({
  selector: 'app-forgot-password-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forgot-password-page.html',
})
export class ForgotPasswordPage {
  private readonly api = inject(AuthenticationService);

  protected readonly maxLengths = MAX_LENGTHS;
  protected readonly sent = signal(false);

  protected readonly form = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: emailValidators }),
  });

  protected readonly state = submitState(this.form);

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const email = this.form.getRawValue().email.trim();
    this.state.begin();

    this.api
      .forgotPassword({ forgotPasswordRequest: { email } }, 'body', false, {
        context: skipAuth(),
      })
      .subscribe({
        next: () => {
          this.state.succeed();
          this.sent.set(true);
        },
        // The one error worth surfacing is throttling: the endpoint is rate limited per
        // address, and a 429 is a real "wait, then try again" that the generic confirmation
        // would hide behind a promise of an email that is not coming.
        error: (error: unknown) => this.state.fail(error),
      });
  }
}
