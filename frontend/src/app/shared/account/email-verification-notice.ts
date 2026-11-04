import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ProfileService } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Alert } from '../ui/alert';

/**
 * Says what an unverified address stops, in the words of the place it is shown, with the way to
 * get a new link. The sentence is the content; whether to show this is the page's decision.
 *
 * <p>The API is what enforces the rule. This is so nobody writes a message, or finishes a listing,
 * to be refused at the end of it.
 */
@Component({
  selector: 'app-email-verification-notice',
  imports: [Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="notice stack" data-email-verification>
      <app-alert kind="info"><ng-content /></app-alert>

      @if (sent()) {
        <app-alert kind="success" data-resend-sent>
          <ng-container i18n="@@verificationSent">Nouveau lien envoyé à</ng-container> {{ address() }}.
          <ng-container i18n="@@verificationSentSpam">Il arrive parfois dans les indésirables.</ng-container>
        </app-alert>
      }
      @if (failure()) {
        <app-alert kind="error" data-resend-failure>{{ failure() }}</app-alert>
      }

      <div>
        <button class="btn btn--quiet" type="button" data-action="resend" [disabled]="sending()" (click)="resend()">
          @if (sending()) {
            <ng-container i18n="@@verificationResending">Envoi…</ng-container>
          } @else {
            <ng-container i18n="@@verificationResend">Renvoyer le lien</ng-container>
          }
        </button>
      </div>
    </div>
  `,
})
export class EmailVerificationNotice {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly errors = inject(ErrorMessageService);

  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly address = () => this.auth.user()?.email ?? 'votre adresse';

  protected resend(): void {
    this.sending.set(true);
    this.sent.set(false);
    this.failure.set(null);
    this.api.resendEmailVerification().subscribe({
      next: () => {
        this.sending.set(false);
        this.sent.set(true);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.failure.set(this.errors.messageFor(error));
      },
    });
  }
}
