import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SavedSearchesService } from '../../core/api';
import { skipAuth } from '../../core/auth/auth.service';
import { Alert } from '../../shared/ui/alert';

/**
 * Where an alert e-mail's unsubscribe link lands. One click and no sign-in: it posts the signed
 * token as it opens. The API only acts on a POST, so a mail scanner fetching the link without
 * running the page does not unsubscribe anybody.
 */
@Component({
  selector: 'app-unsubscribe-page',
  imports: [RouterLink, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="form-column stack">
      <header>
        <h1 class="page-title" i18n="@@unsubscribeTitle">Alerte e-mail</h1>
      </header>
      <div class="panel stack">
        @switch (state()) {
          @case ('working') {
            <p class="meta" aria-busy="true" i18n="@@unsubscribeWorking">Désactivation de l’alerte…</p>
          }
          @case ('done') {
            <app-alert kind="success" data-unsubscribed>
              @if (name(); as searchName) {
                <ng-container i18n="@@unsubscribeDone">Vous ne recevrez plus l’alerte</ng-container> « {{ searchName }} ».
                <ng-container i18n="@@unsubscribeKept">La recherche reste dans vos recherches, en pause.</ng-container>
              } @else {
                <ng-container i18n="@@unsubscribeGone">Cette recherche a été supprimée : vous ne recevrez plus cette alerte.</ng-container>
              }
            </app-alert>
          }
          @case ('failed') {
            <app-alert kind="error" data-unsubscribe-failed i18n="@@unsubscribeFailed">
              Ce lien n’est pas valable. Vous pouvez mettre vos alertes en pause depuis vos recherches.
            </app-alert>
          }
        }
        <p class="meta"><a routerLink="/mes-recherches" i18n="@@unsubscribeMine">Voir mes recherches</a></p>
      </div>
    </div>
  `,
})
export class UnsubscribePage implements OnInit {
  private readonly api = inject(SavedSearchesService);

  /** From the query string. Never rendered. */
  readonly token = input('');

  protected readonly state = signal<'working' | 'done' | 'failed'>('working');
  protected readonly name = signal<string | null>(null);

  ngOnInit(): void {
    const token = this.token().trim();
    if (!token) {
      this.state.set('failed');
      return;
    }
    this.api.unsubscribeFromAlert({ unsubscribeRequest: { token } }, 'body', false, { context: skipAuth() }).subscribe({
      next: (result) => {
        this.name.set(result.name ?? null);
        this.state.set('done');
      },
      error: () => this.state.set('failed'),
    });
  }
}
