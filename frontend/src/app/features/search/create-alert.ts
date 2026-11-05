import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';

import { ErrorCode, SavedSearchesService } from '../../core/api';
import { RETURN_URL_PARAM, SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { EmailVerificationNotice } from '../../shared/account/email-verification-notice';
import { Alert } from '../../shared/ui/alert';
import { describeFilters } from './active-filters';
import { SearchQuery, isLeasing, toQueryParams } from './search-query';
import { SearchResolver } from './search-resolver';

const NAME_MAX = 80;

/**
 * "Créer une alerte": saves the search on the screen, under a name the user can change.
 *
 * <p>What is sent comes from the one parsed query the page shows: the API's criteria from
 * {@link SearchResolver}, as the results themselves are asked for, and the query string beside them
 * for the links back. So an alert is for the search on the screen, whatever its filters.
 *
 * <p>An alert is e-mail, so saving needs a verified address. Everyone sees the button and is told
 * why when they press it, rather than it being hidden from them.
 */
@Component({
  selector: 'app-create-alert',
  imports: [RouterLink, Alert, EmailVerificationNotice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (saved()) {
      <app-alert kind="success" data-alert-saved>
        <ng-container i18n="@@alertSaved">Alerte créée. Les nouvelles annonces vous seront envoyées chaque matin par e-mail.</ng-container>
        <a routerLink="/mes-recherches" i18n="@@alertSeeMine">Voir mes recherches</a>
      </app-alert>
    } @else if (!open()) {
      <button class="btn btn--quiet" type="button" data-action="create-alert" (click)="start()" i18n="@@alertCreate">
        Créer une alerte
      </button>
    } @else {
      <div class="alert-form stack" data-alert-form>
        @if (!signedIn()) {
          <app-alert kind="info" i18n="@@alertSignIn">
            Une alerte vous est envoyée par e-mail : connectez-vous pour en créer une.
          </app-alert>
          <div class="alert-form__actions">
            <a class="btn btn--primary" data-action="sign-in" [routerLink]="signInRoute" [queryParams]="signInQuery()" i18n="@@alertSignInAction">
              Se connecter
            </a>
            <button class="btn btn--quiet" type="button" (click)="close()" i18n="@@cancel">Annuler</button>
          </div>
        } @else if (mustVerify()) {
          <app-email-verification-notice>
            <ng-container i18n="@@alertMustVerify">
              Une alerte vous est envoyée par e-mail : vérifiez d’abord votre adresse, avec le lien que nous vous avons envoyé.
            </ng-container>
          </app-email-verification-notice>
          <div class="alert-form__actions">
            <button class="btn btn--quiet" type="button" (click)="close()" i18n="@@cancel">Annuler</button>
          </div>
        } @else {
          <form class="stack" (submit)="save($event)">
            <div class="field">
              <label class="field__label" for="alert-name" i18n="@@alertName">Nom de l’alerte</label>
              <input
                id="alert-name"
                class="input"
                type="text"
                [value]="name()"
                [attr.maxlength]="nameMax"
                (input)="edit($event)"
              />
            </div>
            <p class="meta" i18n="@@alertWhat">Un e-mail chaque matin s’il y a de nouvelles annonces, jamais sinon.</p>
            @if (failure()) {
              <app-alert kind="error" data-alert-failure>{{ failure() }}</app-alert>
            }
            <div class="alert-form__actions">
              <button class="btn btn--primary" type="submit" data-action="save-alert" [disabled]="!canSave()" i18n="@@alertSave">
                Enregistrer
              </button>
              <button class="btn btn--quiet" type="button" (click)="close()" i18n="@@cancel">Annuler</button>
            </div>
          </form>
        }
      </div>
    }
  `,
  styles: `
    .alert-form {
      padding: var(--s-3);
      border: 1px solid var(--c-line);
      border-radius: var(--r-sm);
      background: var(--c-surface);
    }

    .alert-form__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-2);
    }
  `,
})
export class CreateAlert {
  private readonly api = inject(SavedSearchesService);
  private readonly auth = inject(AuthService);
  private readonly resolver = inject(SearchResolver);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);

  readonly query = input.required<SearchQuery>();

  protected readonly nameMax = NAME_MAX;
  protected readonly signInRoute = SIGN_IN_ROUTE;
  protected readonly signedIn = this.auth.isAuthenticated;
  /** Any account whose address is not verified: an older one too, unlike publishing. */
  protected readonly mustVerify = computed(() => this.auth.user()?.emailVerified === false);
  protected readonly signInQuery = computed(() => ({ [RETURN_URL_PARAM]: this.router.url }));

  protected readonly open = signal(false);
  protected readonly saved = signal(false);
  protected readonly saving = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly name = signal('');
  protected readonly canSave = computed(() => this.name().trim().length > 0 && !this.saving());

  protected start(): void {
    this.failure.set(null);
    this.open.set(true);
    this.name.set(defaultName(this.query()));
    this.resolver.names(this.query()).subscribe((names) => this.name.set(defaultName(this.query(), names)));
  }

  protected close(): void {
    this.open.set(false);
  }

  protected edit(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected save(event: Event): void {
    event.preventDefault();
    if (!this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.failure.set(null);
    const query = this.query();
    this.resolver
      .criteria(query)
      .pipe(
        switchMap((criteria) =>
          this.api.saveSearch({
            createSavedSearchRequest: { name: this.name().trim(), criteria, params: toQueryParams(query) },
          }),
        ),
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.open.set(false);
          this.saved.set(true);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          if (asApiError(error)?.code === ErrorCode.EMAIL_NOT_VERIFIED) {
            // The session's summary was out of date: say it the way the notice does.
            const user = this.auth.user();
            this.auth.setUser({ ...user, emailVerified: false });
          }
          this.failure.set(this.errors.messageFor(error));
        },
      });
  }
}

/** "Audi · A3 · Diesel", from the chips the page shows, or what the search is for with none. */
export function defaultName(query: SearchQuery, names = {}): string {
  const filters = describeFilters(query, names).map((filter) => filter.label);
  const name = filters.length > 0 ? filters.join(' · ') : isLeasing(query) ? 'Toutes les locations' : 'Toutes les annonces';
  return name.slice(0, NAME_MAX);
}
