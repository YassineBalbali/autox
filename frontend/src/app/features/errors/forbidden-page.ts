import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

/**
 * Where `roleGuard` sends a signed-in user who may not see a route.
 *
 * <p>Separate from a sign-in redirect, and that distinction is the reason this page exists.
 * Sending somebody back to a sign-in form when they are already signed in — correctly, as
 * the wrong kind of account — reads as "your session broke", and they will try their
 * password again and fail in a way they cannot diagnose. This says what is actually true.
 *
 * <p>It names the account in use, because the usual cause is having two: a private account
 * following a link meant for the dealer one. Signing out is therefore the action offered,
 * next to a way back.
 */
@Component({
  selector: 'app-forbidden-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="form-column stack">
      <header>
        <h1 class="page-title" i18n="@@forbiddenTitle">Accès refusé</h1>
      </header>

      <div class="panel stack">
        <p i18n="@@forbiddenBody">
          Cette page n’est pas accessible avec votre type de compte.
        </p>

        @if (email()) {
          <p class="meta" i18n="@@forbiddenSignedInAs">
            Vous êtes connecté avec l’adresse {{ email() }}.
          </p>
        }

        <a class="btn btn--primary btn--block" routerLink="/mon-compte" i18n="@@backToAccount">
          ← Mon compte
        </a>

        <button
          class="btn btn--quiet btn--block"
          type="button"
          (click)="signOut()"
          i18n="@@forbiddenSwitchAccount"
        >
          Se connecter avec un autre compte
        </button>
      </div>
    </div>
  `,
})
export class ForbiddenPage {
  private readonly auth = inject(AuthService);

  protected readonly email = () => this.auth.user()?.email ?? null;

  protected signOut(): void {
    this.auth.logout('/connexion');
  }
}
