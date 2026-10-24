import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';

import { ErrorCode, Feature, ListingsPublicService, PublicListingDetail } from '../../core/api';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { FUEL_LABELS, GEARBOX_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { PhotoGallery } from '../../shared/listing/photo-gallery';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { NotFound } from '../../shared/ui/not-found';
import { SearchMemory, isSearchUrl } from '../search/search-memory';
import { KeyFacts } from './key-facts';
import { ListingSummary } from './listing-summary';
import { galleryImages, groupFeatures, specificationRows } from './listing-view';
import { SimilarListings } from './similar-listings';

type PageState = 'loading' | 'ready' | 'missing' | 'error';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * One listing, as a buyer sees it.
 *
 * <p>Public, like the search: no session is needed and none is used. The API only ever
 * returns a listing that is live, so this page has exactly four states and "the listing
 * exists but you may not see it" is not one of them — a draft, a sold or a removed
 * listing is, to this page, the same as an address that never existed.
 *
 * <h2>Not found is a state, not a failure</h2>
 *
 * <p>Listings sell, expire and are withdrawn; a link to one is a link that will one day
 * stop working. So "this listing is gone" gets its own screen with a way back to the
 * search, and is kept apart from "the request failed", which gets a retry. Telling
 * somebody to retry a listing that has been sold is as wrong as telling them a listing
 * is gone because their train went into a tunnel.
 */
@Component({
  selector: 'app-listing-page',
  imports: [PhotoGallery, KeyFacts, ListingSummary, SimilarListings, Icon, Alert, NotFound],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './listing-page.html',
  styleUrl: './listing-page.scss',
})
export class ListingPage {
  private readonly api = inject(ListingsPublicService);
  private readonly reference = inject(ReferenceStore);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly title = inject(Title);
  private readonly memory = inject(SearchMemory);

  /** The listing id, bound from the route by `withComponentInputBinding`. */
  readonly id = input.required<string>();

  protected readonly state = signal<PageState>('loading');
  protected readonly listing = signal<PublicListingDetail | null>(null);
  protected readonly failure = signal<string | null>(null);

  /**
   * Whether the visitor arrived here from a page of results, decided once, at arrival.
   *
   * <p>It changes what "back to the results" does. Coming from the search, it goes back
   * in history — which is what restores the list as it was, loaded pages and scroll
   * position included. Arriving any other way (a shared link, a bookmark) there is no
   * such entry to go back to, and stepping back would leave the site altogether.
   */
  private readonly cameFromSearch = isSearchUrl(
    this.router.getCurrentNavigation()?.previousNavigation?.finalUrl?.toString(),
  );

  /** Where "back to the results" leads when it cannot simply go back. */
  protected readonly resultsUrl = this.memory.lastUrl() ?? '/recherche';

  private readonly features = toSignal(
    this.reference.features().pipe(catchError(() => of([] as Feature[]))),
    { initialValue: [] as Feature[] },
  );

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected readonly images = computed(() => {
    const listing = this.listing();
    return listing ? galleryImages(listing) : [];
  });

  protected readonly specification = computed(() => {
    const listing = this.listing();
    return listing ? specificationRows(listing) : [];
  });

  protected readonly equipment = computed(() =>
    groupFeatures(this.listing()?.featureCodes, this.features()),
  );

  protected readonly minorUnit = computed(
    () => this.minorUnits()[this.listing()?.currencyCode ?? ''] ?? DEFAULT_MINOR_UNIT,
  );

  protected readonly fuelLabel = computed(() => labelOf(FUEL_LABELS, this.listing()?.vehicle?.fuelType));
  protected readonly gearboxLabel = computed(() => labelOf(GEARBOX_LABELS, this.listing()?.vehicle?.gearbox));

  constructor() {
    toObservable(this.id)
      .pipe(
        tap(() => {
          this.state.set('loading');
          this.listing.set(null);
          this.failure.set(null);
        }),
        // switchMap: a second listing opened while the first is loading replaces it.
        switchMap((id) => this.fetch(id)),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  protected retry(): void {
    this.state.set('loading');
    this.fetch(this.id()).subscribe();
  }

  /**
   * "Retour aux résultats".
   *
   * <p>A real link with a real `href`, so it can be opened in a new tab and works before
   * the script does — but an ordinary click is intercepted, for the reason given on
   * {@link cameFromSearch}.
   */
  protected backToResults(event: MouseEvent): void {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
      // A new tab or window was asked for. That is the href's job.
      return;
    }
    event.preventDefault();
    if (this.cameFromSearch) {
      this.location.back();
    } else {
      void this.router.navigateByUrl(this.resultsUrl);
    }
  }

  private fetch(id: string) {
    if (!UUID.test(id)) {
      // Not something the API could ever have issued. Asking would get a 400 back, which
      // means the same thing to the visitor and costs a round trip to learn.
      this.showMissing();
      return of(null);
    }

    return this.api.detail({ id }).pipe(
      tap((listing) => {
        this.listing.set(listing);
        this.state.set('ready');
        this.title.setTitle(`${listing.title ?? 'Annonce'} · AutoX`);
      }),
      catchError((error: unknown) => {
        if (isNotFound(error)) {
          this.showMissing();
        } else {
          this.failure.set(this.errors.messageFor(error));
          this.state.set('error');
        }
        return of(null);
      }),
    );
  }

  private showMissing(): void {
    this.state.set('missing');
    this.title.setTitle('Annonce introuvable · AutoX');
  }
}

/**
 * Whether a failed request means "there is no such listing".
 *
 * <p>The API's `NOT_FOUND`, and a bare 404 from anything in front of it. Not a 5xx and
 * not a network failure: those say nothing about whether the listing exists.
 */
function isNotFound(error: unknown): boolean {
  return (
    asApiError(error)?.code === ErrorCode.NOT_FOUND ||
    (error instanceof HttpErrorResponse && error.status === 404)
  );
}
