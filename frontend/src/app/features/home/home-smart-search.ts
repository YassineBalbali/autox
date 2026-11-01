import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';

import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Icon } from '../../shared/ui/icon';
import { SEARCH_PATH } from '../search/legacy-search-redirect';
import { EMPTY_QUERY, toQueryParams } from '../search/search-query';
import { INTERPRETATION_STATE, MAX_DESCRIPTION, SmartSearch } from '../search/smart-search';

type State = { kind: 'idle' } | { kind: 'loading' } | { kind: 'failed'; message: string; text: string };

/** `ListingSearchCriteria.q` is `@Size(max = 120)`: the fallback search is cut to fit. */
const MAX_TEXT_QUERY = 120;

/**
 * "Recherche intelligente": a description of a car in the visitor's own words, turned into
 * the filters of the ordinary search.
 *
 * <p>A way in to the same results page as everything else on the home page. The server
 * interprets; this navigates to `/recherche` with the filters it returned, exactly as if they
 * had been chosen by hand, and hands the results page the description and what was not
 * understood, to show above the filter chips.
 *
 * <p>Absent when the server has no provider. When it has one and it fails — slow, out of
 * quota, down — the visitor is told so and offered the ordinary text search for the same
 * words: a language model being unavailable must never cost them the search.
 */
@Component({
  selector: 'app-home-smart-search',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './home-smart-search.scss',
  template: `
    @if (smart.available()) {
      <section class="smart" aria-labelledby="smart-title">
        <form class="smart__form" (submit)="submit($event, field.value)">
          <h2 id="smart-title" class="smart__title">
            <app-icon name="sparkles" [size]="18" />
            <ng-container i18n="@@smartTitle">Recherche intelligente</ng-container>
          </h2>
          <label class="visually-hidden" for="smart-description" i18n="@@smartLabel">
            Décrivez la voiture que vous cherchez
          </label>
          <input
            #field
            id="smart-description"
            class="smart__input"
            type="text"
            autocomplete="off"
            [attr.maxlength]="maxLength"
            placeholder="Décrivez la voiture que vous cherchez"
            i18n-placeholder="@@smartPlaceholder"
            aria-describedby="smart-hint"
            [disabled]="state().kind === 'loading'"
          />
          <button class="btn btn--primary smart__submit" type="submit" [disabled]="state().kind === 'loading'">
            @if (state().kind === 'loading') {
              <span class="smart__spinner" aria-hidden="true"></span>
              <ng-container i18n="@@smartLoading">Analyse…</ng-container>
            } @else {
              <app-icon name="search" [size]="18" />
              <ng-container i18n="@@smartSubmit">Rechercher</ng-container>
            }
          </button>
          <p id="smart-hint" class="smart__hint" i18n="@@smartHint">
            Par exemple : « SUV diesel automatique moins de 15 000 € »
          </p>
        </form>

        <div aria-live="polite">
          @if (failure(); as failed) {
            <p class="smart__error" role="alert">
              {{ failed.message }}
              <button class="smart__fallback" type="button" (click)="textSearch(failed.text)" i18n="@@smartFallback">
                Lancer une recherche classique
              </button>
            </p>
          }
        </div>
      </section>
    }
  `,
})
export class HomeSmartSearch {
  protected readonly smart = inject(SmartSearch);
  private readonly router = inject(Router);
  private readonly errors = inject(ErrorMessageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly maxLength = MAX_DESCRIPTION;
  protected readonly state = signal<State>({ kind: 'idle' });

  protected failure(): { message: string; text: string } | null {
    const state = this.state();
    return state.kind === 'failed' ? state : null;
  }

  protected submit(event: Event, value: string): void {
    event.preventDefault();
    const text = value.trim();
    if (!text || this.state().kind === 'loading') {
      return;
    }
    this.state.set({ kind: 'loading' });
    this.smart
      .interpret(text)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ query, interpretation }) => {
          this.state.set({ kind: 'idle' });
          void this.router.navigate([SEARCH_PATH], {
            queryParams: toQueryParams(query),
            state: { [INTERPRETATION_STATE]: interpretation },
          });
        },
        error: (error: unknown) => {
          this.state.set({ kind: 'failed', message: this.errors.translate(error).message, text });
        },
      });
  }

  /** The fallback: the same words as an ordinary search, every word to match. */
  protected textSearch(text: string): void {
    void this.router.navigate([SEARCH_PATH], {
      queryParams: toQueryParams({ ...EMPTY_QUERY, text: text.slice(0, MAX_TEXT_QUERY) }),
    });
  }
}
