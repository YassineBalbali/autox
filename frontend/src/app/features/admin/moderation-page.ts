import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';

import { AdminService, ModeratedListing, ModerationActionType, ModerationView } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { countFr } from '../../shared/format/plural';
import { Alert } from '../../shared/ui/alert';
import { ModerationCard } from './moderation-card';
import { ModerationDecision } from './moderation-decision';
import { ModerationFilters } from './moderation-filters';
import { ModerationLayout, ModerationLayoutPreference, PAGE_SIZES } from './moderation-layout';
import { EMPTY_QUERY, ModerationQuery, isNarrowed, paramsFromQuery, queryFromParams, requestFromQuery } from './moderation-query';
import { ModerationRow } from './moderation-row';

/** The three lists, by the name each has in the URL (`?vue=`). The first is the default. */
export const VIEWS: readonly { view: ModerationView; slug: string; label: string; empty: string }[] = [
  {
    view: ModerationView.PENDING,
    slug: 'a-verifier',
    label: 'À vérifier',
    empty: 'Aucune annonce en attente de vérification. La file est vide.',
  },
  { view: ModerationView.LIVE, slug: 'en-ligne', label: 'En ligne', empty: 'Aucune annonce en ligne.' },
  { view: ModerationView.REMOVED, slug: 'retirees', label: 'Retirées', empty: 'Aucune annonce retirée.' },
];

/** The view a slug names; the queue for none, or for one this page does not know. */
export function viewOfSlug(slug: string | undefined) {
  return VIEWS.find((candidate) => candidate.slug === slug) ?? VIEWS[0];
}

/** Whole listings with all their photos: few per page. The list of rows takes more. */
export const PAGE_SIZE = PAGE_SIZES.cartes;

type LoadState = 'loading' | 'ready' | 'error';

/** What to say once a decision has gone through. */
const DONE: Record<ModerationActionType, string> = {
  [ModerationActionType.APPROVE]: 'Annonce approuvée.',
  [ModerationActionType.REJECT]: 'Annonce refusée : elle n’est plus visible.',
  [ModerationActionType.REMOVE]: 'Annonce retirée : elle n’est plus visible.',
  [ModerationActionType.REINSTATE]: 'Annonce réintégrée.',
};

/**
 * Moderation: the queue, what is online, and what was taken down — each searchable and
 * filterable, as cards to judge a listing on or as rows to find one among many.
 *
 * <p>The view, the search, the filters, the order and the page number live in the URL, so a
 * moderator can reload, share a link to "en ligne, Peugeot, professionnels, page 3", or come
 * back to where they were. Moving to another list keeps the search and the filters and starts
 * again from its first page, in its own order. Cards or rows is a preference, kept per browser
 * (`ModerationLayoutPreference`).
 *
 * <p>After every decision the current page is fetched again rather than patched in place.
 * A listing that was approved leaves the queue, one that was taken down leaves "en ligne",
 * and the count changes with them; asking the API is the only version of that which is
 * right when a colleague is working the same queue. For the same reason a refusal from the
 * API — the listing was decided by someone else a second ago — is shown on its card in
 * French and followed by the same refetch.
 *
 * <p>The route is guarded for the ADMIN role, and that guard is a courtesy: every call
 * this page makes is refused by the API for anyone else.
 */
@Component({
  selector: 'app-moderation-page',
  imports: [RouterLink, Alert, ModerationCard, ModerationFilters, ModerationRow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './moderation-page.html',
  styleUrl: './moderation-page.scss',
})
export class ModerationPage {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);
  private readonly preference = inject(ModerationLayoutPreference);
  private readonly reference = inject(ReferenceStore);

  /** `?vue=` — which list. */
  readonly vue = input<string | undefined>();
  /** `?page=` — one-based, as people count. */
  readonly page = input<string | undefined>();

  protected readonly views = VIEWS;
  protected readonly current = computed(() => viewOfSlug(this.vue()));

  /** The search, the filters and the order, from the URL. */
  private readonly params = toSignal(inject(ActivatedRoute).queryParams, { initialValue: {} });
  protected readonly query = computed<ModerationQuery>(() => queryFromParams(this.params()));
  protected readonly narrowed = computed(() => isNarrowed(this.query()));

  protected readonly layout = this.preference.layout;
  private readonly pageSize = computed(() => PAGE_SIZES[this.layout()]);

  /** The catalogue's makes, for the filter; null until they come, and if they never do. */
  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.map((make) => make.name ?? '').filter(Boolean)),
      catchError(() => of(null)),
    ),
    { initialValue: null },
  );

  /** Zero-based, as the API counts. Anything that is not a positive whole number is the first page. */
  protected readonly pageIndex = computed(() => {
    const asked = Number(this.page());
    return Number.isInteger(asked) && asked >= 1 ? asked - 1 : 0;
  });

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly items = signal<ModeratedListing[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);

  protected readonly notice = signal<string | null>(null);
  /** The listing a decision is running on. One at a time. */
  protected readonly busy = signal<string | null>(null);
  /** A refused decision: which listing, and why. */
  protected readonly failure = signal<{ id: string; message: string } | null>(null);

  protected readonly countLine = computed(() => {
    const total = this.total();
    return total === 0 ? null : `${total} annonce${total > 1 ? 's' : ''}`;
  });
  /** How many listings the list holds, or the search found. */
  protected readonly resultLine = computed(() =>
    this.narrowed()
      ? countFr(this.total(), 'annonce trouvée', 'annonces trouvées')
      : countFr(this.total(), 'annonce', 'annonces'),
  );
  protected readonly hasPrevious = computed(() => this.pageIndex() > 0);
  protected readonly hasNext = computed(() => this.pageIndex() + 1 < this.totalPages());

  /** A tab keeps the search and the filters, and starts its list from the top in its own order. */
  protected readonly tabParams = computed(() => {
    const kept = paramsFromQuery({ ...this.query(), sort: null }, this.current().view);
    return VIEWS.map((entry, index) => ({ ...kept, vue: index === 0 ? null : entry.slug }));
  });

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  /** Guards against an answer for a view the moderator has already left. */
  private generation = 0;
  /** Set while the page number is being corrected after the last page emptied. */
  private steppingBack = false;

  constructor() {
    // A different list, page, search or filter is a new fetch — and a clean slate.
    effect(() => {
      this.current();
      this.pageIndex();
      this.query();
      this.pageSize();
      if (this.steppingBack) {
        // Not the moderator moving on: the page they were on emptied under them. What
        // they were just told about their decision still applies.
        this.steppingBack = false;
      } else {
        this.notice.set(null);
        this.failure.set(null);
      }
      this.state.set('loading');
      this.fetch();
    });
  }

  protected minorUnit(currency: string | undefined): number {
    return this.minorUnits()[currency ?? 'EUR'] ?? DEFAULT_MINOR_UNIT;
  }

  protected failureFor(item: ModeratedListing): string | null {
    const failure = this.failure();
    return failure && failure.id === item.listing?.id ? failure.message : null;
  }

  /**
   * A refusal whose listing is no longer on this page — typically because the refetch
   * that followed showed it had already left this list. The explanation still has to be
   * said somewhere, so it is said above the list.
   */
  protected readonly strayFailure = computed(() => {
    const failure = this.failure();
    if (!failure || this.state() !== 'ready') {
      return null;
    }
    return this.items().some((item) => item.listing?.id === failure.id) ? null : failure.message;
  });

  protected reload(): void {
    this.state.set('loading');
    this.fetch();
  }

  /** A new search or filter: into the URL, from the first page. */
  protected onQuery(query: ModerationQuery): void {
    void this.router.navigate([], {
      queryParams: { ...paramsFromQuery(query, this.current().view), page: null },
      queryParamsHandling: 'merge',
    });
  }

  protected clearAll(): void {
    this.onQuery({ ...EMPTY_QUERY, sort: this.query().sort });
  }

  /** Cards or rows. A page of rows holds more, so the list starts again from the top. */
  protected onLayout(layout: ModerationLayout): void {
    if (layout === this.layout()) {
      return;
    }
    this.preference.set(layout);
    if (this.pageIndex() > 0) {
      void this.router.navigate([], { queryParams: { page: null }, queryParamsHandling: 'merge' });
    }
  }

  private fetch(): void {
    const generation = ++this.generation;
    const index = this.pageIndex();
    // Untracked: the currencies arriving is not a reason to ask for the list again. Prices are
    // asked in euros; until the currencies are in, a euro has the usual two decimals.
    const request = requestFromQuery(this.query(), this.current().view, untracked(() => this.minorUnit('EUR')));
    this.api.moderationList({ ...request, page: index, size: this.pageSize() }).subscribe({
      next: (answer) => {
        if (generation !== this.generation) {
          return;
        }
        const items = answer.items ?? [];
        if (items.length === 0 && index > 0) {
          // The last listing on the last page was just decided. Step back rather than
          // show an empty page with "précédent" as the only way out.
          this.steppingBack = true;
          void this.router.navigate([], { queryParams: { page: index === 1 ? null : index }, queryParamsHandling: 'merge' });
          return;
        }
        this.items.set(items);
        this.total.set(answer.totalElements ?? items.length);
        this.totalPages.set(answer.totalPages ?? 1);
        this.loadFailure.set(null);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        if (generation !== this.generation) {
          return;
        }
        this.loadFailure.set(this.errors.messageFor(error));
        this.state.set('error');
      },
    });
  }

  protected decide(item: ModeratedListing, decision: ModerationDecision): void {
    const id = item.listing?.id;
    if (!id || this.busy() !== null) {
      return;
    }
    this.notice.set(null);
    this.failure.set(null);
    this.busy.set(id);

    this.requestFor(id, decision).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(DONE[decision.action]);
        this.fetch();
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.failure.set({ id, message: this.errors.messageFor(error) });
        // Refused usually means somebody else decided first. Show what is true now.
        this.fetch();
      },
    });
  }

  private requestFor(id: string, decision: ModerationDecision): Observable<ModeratedListing> {
    const note = decision.note ? { note: decision.note } : undefined;
    switch (decision.action) {
      case ModerationActionType.APPROVE:
        return this.api.approve({ id, moderationNoteRequest: note });
      case ModerationActionType.REINSTATE:
        return this.api.reinstate({ id, moderationNoteRequest: note });
      case ModerationActionType.REJECT:
        return this.api.reject({ id, moderationDecisionRequest: { reasonCode: decision.reasonCode!, ...note } });
      case ModerationActionType.REMOVE:
        return this.api.remove({ id, moderationDecisionRequest: { reasonCode: decision.reasonCode!, ...note } });
    }
  }
}
