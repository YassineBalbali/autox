import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { SavedSearchView, SavedSearchesService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Alert } from '../../shared/ui/alert';

/** The API's limit, for the line that says how many are left. */
const MAX_SEARCHES = 10;

/**
 * "Mes recherches": the searches an account has saved, what is new in each since it was last
 * opened, and the way to rename, pause, resume, delete or open one.
 *
 * <p>The list is only ever what the API last answered: every action replaces its row, or reloads.
 */
@Component({
  selector: 'app-my-searches-page',
  imports: [RouterLink, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-searches-page.html',
  styleUrl: './my-searches-page.scss',
})
export class MySearchesPage implements OnInit {
  private readonly api = inject(SavedSearchesService);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);

  protected readonly max = MAX_SEARCHES;
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly searches = signal<SavedSearchView[]>([]);
  protected readonly failure = signal<string | null>(null);
  protected readonly renaming = signal<string | null>(null);
  protected readonly draftName = signal('');
  protected readonly confirmingDelete = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.api.listSavedSearches().subscribe({
      next: (searches) => {
        this.searches.set(searches);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.failure.set(this.errors.messageFor(error));
        this.state.set('error');
      },
    });
  }

  /** Marks it visited, so its count starts again, then shows it; a failed mark does not stop that. */
  protected open(search: SavedSearchView): void {
    const show = () => void this.router.navigate(['/recherche'], { queryParams: search.params });
    this.api.visitSavedSearch({ id: search.id }).subscribe({ next: show, error: show });
  }

  protected startRename(search: SavedSearchView): void {
    this.failure.set(null);
    this.draftName.set(search.name);
    this.renaming.set(search.id);
  }

  protected cancelRename(): void {
    this.renaming.set(null);
  }

  protected editName(event: Event): void {
    this.draftName.set((event.target as HTMLInputElement).value);
  }

  protected rename(event: Event, search: SavedSearchView): void {
    event.preventDefault();
    const name = this.draftName().trim();
    if (!name) {
      return;
    }
    this.change(search, { name }, () => this.renaming.set(null));
  }

  protected togglePause(search: SavedSearchView): void {
    this.change(search, { paused: !search.paused });
  }

  protected askDelete(search: SavedSearchView): void {
    this.failure.set(null);
    this.confirmingDelete.set(search.id);
  }

  protected cancelDelete(): void {
    this.confirmingDelete.set(null);
  }

  protected remove(search: SavedSearchView): void {
    this.api.deleteSavedSearch({ id: search.id }).subscribe({
      next: () => {
        this.confirmingDelete.set(null);
        this.searches.update((all) => all.filter((each) => each.id !== search.id));
      },
      error: (error: unknown) => this.failure.set(this.errors.messageFor(error)),
    });
  }

  /** "3 nouvelles annonces", "Plus de 99 nouvelles annonces", or "Rien de nouveau". */
  protected newLabel(search: SavedSearchView): string {
    if (search.newCountCapped) {
      return `Plus de ${search.newCount} nouvelles annonces`;
    }
    if (search.newCount === 0) {
      return 'Rien de nouveau depuis votre dernière visite';
    }
    return search.newCount === 1 ? '1 nouvelle annonce' : `${search.newCount} nouvelles annonces`;
  }

  private change(search: SavedSearchView, changes: { name?: string; paused?: boolean }, done?: () => void): void {
    this.failure.set(null);
    this.api.updateSavedSearch({ id: search.id, updateSavedSearchRequest: changes }).subscribe({
      next: (updated) => {
        this.searches.update((all) => all.map((each) => (each.id === updated.id ? updated : each)));
        done?.();
      },
      error: (error: unknown) => this.failure.set(this.errors.messageFor(error)),
    });
  }
}
