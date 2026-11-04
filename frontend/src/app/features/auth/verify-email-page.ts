import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthenticationService, ProfileService } from '../../core/api';
import { SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService, skipAuth, userSummaryOf } from '../../core/auth/auth.service';
import { EmailVerificationNotice } from '../../shared/account/email-verification-notice';
import { Alert } from '../../shared/ui/alert';

/**
 * Where the verification email's link lands. It spends the token as soon as it opens: unlike a
 * reset there is nothing to fill in, and the link is what the person clicked to do this.
 *
 * <p>The API only spends a token on a POST, so a mail scanner that fetches the link without running
 * the page leaves it working.
 */
@Component({
  selector: 'app-verify-email-page',
  imports: [RouterLink, Alert, EmailVerificationNotice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="form-column stack">
      <header>
        <h1 class="page-title" i18n="@@verifyTitle">Vérification de votre adresse</h1>
      </header>

      <div class="panel stack">
        @switch (state()) {
          @case ('verifying') {
            <p class="meta" aria-busy="true" i18n="@@verifyWorking">Vérification en cours…</p>
          }
          @case ('verified') {
            <app-alert kind="success" i18n="@@verifyDone">
              Votre adresse e-mail est vérifiée. Vous pouvez publier des annonces et envoyer des messages.
            </app-alert>
            <p class="meta"><a routerLink="/" i18n="@@verifyHome">Retour à l’accueil</a></p>
          }
          @case ('failed') {
            @if (alreadyVerified()) {
              <app-alert kind="success" i18n="@@verifyAlready">
                Ce lien a déjà servi : votre adresse e-mail est vérifiée, il n’y a rien d’autre à faire.
              </app-alert>
              <p class="meta"><a routerLink="/" i18n="@@verifyHome">Retour à l’accueil</a></p>
            } @else if (signedIn()) {
              <app-email-verification-notice>
                <ng-container i18n="@@verifyFailedSignedIn">
                  Ce lien n’est plus valable : il a expiré, ou un plus récent l’a remplacé. Demandez-en un nouveau.
                </ng-container>
              </app-email-verification-notice>
            } @else {
              <app-alert kind="error" i18n="@@verifyFailed">
                Ce lien n’est plus valable : il a expiré, ou il a déjà servi.
              </app-alert>
              <p class="meta">
                <a [routerLink]="signInRoute" i18n="@@verifySignIn">Connectez-vous</a>
                <ng-container i18n="@@verifySignInAfter"> pour demander un nouveau lien depuis votre compte.</ng-container>
              </p>
            }
          }
        }
      </div>
    </div>
  `,
})
export class VerifyEmailPage implements OnInit {
  private readonly api = inject(AuthenticationService);
  private readonly profile = inject(ProfileService);
  private readonly auth = inject(AuthService);

  /** The token from the link, bound from the query string. Never rendered. */
  readonly token = input('');

  protected readonly signInRoute = SIGN_IN_ROUTE;
  protected readonly signedIn = this.auth.isAuthenticated;
  protected readonly state = signal<'verifying' | 'verified' | 'failed'>('verifying');
  /** The usual dead link: clicked a second time. Nothing to ask for, and a new link would not be sent. */
  protected readonly alreadyVerified = computed(() => this.auth.user()?.emailVerified === true);

  ngOnInit(): void {
    const token = this.token().trim();
    if (!token) {
      this.state.set('failed');
      return;
    }
    this.api.verifyEmail({ verifyEmailRequest: { token } }, 'body', false, { context: skipAuth() }).subscribe({
      next: () => {
        this.state.set('verified');
        // The header and the notices read the session's summary, which still says unverified.
        if (this.signedIn()) {
          this.profile.getOwnProfile().subscribe({
            next: (me) => this.auth.setUser(userSummaryOf(me)),
            error: () => undefined,
          });
        }
      },
      error: () => this.state.set('failed'),
    });
  }
}
