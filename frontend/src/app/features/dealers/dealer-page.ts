import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, distinctUntilChanged, map, of, switchMap, tap } from 'rxjs';

import { DealerPage as DealerPageView, DealersService, ErrorCode } from '../../core/api';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { PageMeta } from '../../core/ui/page-meta';
import { DealerLogo } from '../../shared/dealer/dealer-logo';
import { departementLabel } from '../../shared/dealer/departements.fr';
import { countFr } from '../../shared/format/plural';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { NotFound } from '../../shared/ui/not-found';
import { ContactSeller } from '../listing/contact-seller';
import { SearchPage } from '../search/search-page';
import { memberSinceFr } from './dealer-directory-page';

/**
 * A professional's page, at `/professionnels/<slug>`: who they are, how to reach them, and their
 * listings.
 *
 * <p>The listings are the search page itself, given the dealer's account: the same filters, the
 * same sort, sale or leasing, the same URL as state — this page adds nothing of its own to how
 * listings are found, so it cannot drift from the search.
 *
 * <p>Reaching them is AutoX's messaging, the same form as on a listing. A phone number only if
 * the dealer chose to show it; never an e-mail address, which the API does not send.
 */
@Component({
  selector: 'app-dealer-page',
  imports: [RouterLink, Alert, Icon, NotFound, DealerLogo, ContactSeller, SearchPage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dealer-page.html',
  styleUrl: './dealer-page.scss',
})
export class DealerPage {
  private readonly api = inject(DealersService);
  private readonly errors = inject(ErrorMessageService);
  private readonly meta = inject(PageMeta);

  /** From the route, through `withComponentInputBinding()`. */
  readonly slug = input.required<string>();

  protected readonly state = signal<'loading' | 'ready' | 'missing' | 'error'>('loading');
  protected readonly failure = signal<string | null>(null);
  protected readonly dealer = signal<DealerPageView | null>(null);

  protected readonly place = computed(() => {
    const dealer = this.dealer();
    if (!dealer?.city) {
      return null;
    }
    const departement = departementLabel(dealer.departement);
    return departement ? `${dealer.city} — ${departement}` : dealer.city;
  });
  protected readonly memberSince = computed(() => memberSinceFr(this.dealer()?.memberSince));
  protected readonly listingsText = computed(() => {
    const dealer = this.dealer();
    const sale = dealer?.saleListings ?? 0;
    const leasing = dealer?.leasingListings ?? 0;
    const text = countFr(sale, 'annonce en ligne', 'annonces en ligne');
    return leasing > 0 ? `${text} · ${countFr(leasing, 'offre', 'offres')} de location` : text;
  });
  /** A tel: link wants the number alone: "+33472000000". */
  protected readonly phoneHref = computed(() => {
    const phone = this.dealer()?.phone;
    return phone ? `tel:${phone.replace(/[^\d+]/g, '')}` : null;
  });
  /** The address without its scheme, as people say it: "garage-bellevue.fr/occasions". */
  protected readonly websiteLabel = computed(() =>
    (this.dealer()?.websiteUrl ?? '').replace(/^https?:\/\//i, '').replace(/\/$/, ''),
  );

  constructor() {
    toObservable(this.slug)
      .pipe(
        distinctUntilChanged(),
        tap(() => {
          this.state.set('loading');
          this.failure.set(null);
        }),
        switchMap((slug) =>
          this.api.dealerPage({ slug }).pipe(
            map((dealer) => ({ dealer, error: null as unknown })),
            catchError((error: unknown) => of({ dealer: null, error })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ dealer, error }) => {
        if (dealer) {
          this.dealer.set(dealer);
          this.state.set('ready');
          this.meta.describe(describe(dealer));
        } else if (isNotFound(error)) {
          this.dealer.set(null);
          this.state.set('missing');
          this.meta.describe({
            title: 'Professionnel introuvable · AutoX',
            description: 'Cette page de professionnel n’existe pas ou n’est plus en ligne.',
          });
        } else {
          this.failure.set(this.errors.messageFor(error));
          this.state.set('error');
        }
      });

    inject(DestroyRef).onDestroy(() => this.meta.reset());
  }

  protected retry(): void {
    const slug = this.slug();
    this.state.set('loading');
    this.api.dealerPage({ slug }).subscribe({
      next: (dealer) => {
        this.dealer.set(dealer);
        this.state.set('ready');
        this.meta.describe(describe(dealer));
      },
      error: (error: unknown) => {
        this.failure.set(this.errors.messageFor(error));
        this.state.set(isNotFound(error) ? 'missing' : 'error');
      },
    });
  }
}

/**
 * The tab's title and the link preview: the name and the town, then what the dealer wrote about
 * themselves, or failing that what they have online.
 */
export function describe(dealer: DealerPageView): { title: string; description: string; image: string | null } {
  const name = dealer.name ?? 'Professionnel';
  const title = dealer.city ? `${name}, professionnel à ${dealer.city} · AutoX` : `${name} · AutoX`;
  const listings = countFr(
    (dealer.saleListings ?? 0) + (dealer.leasingListings ?? 0),
    'annonce en ligne',
    'annonces en ligne',
  );
  const written = (dealer.description ?? '').replace(/\s+/g, ' ').trim();
  const description = written
    ? clip(written, 160)
    : `${name}${dealer.city ? `, à ${dealer.city}` : ''} : ${listings} sur AutoX, coordonnées et horaires.`;
  return { title, description, image: dealer.logoUrl ?? null };
}

function clip(text: string, length: number): string {
  return text.length <= length ? text : `${text.slice(0, length - 1).replace(/\s+\S*$/, '')}…`;
}

function isNotFound(error: unknown): boolean {
  return (
    asApiError(error)?.code === ErrorCode.NOT_FOUND ||
    (error instanceof HttpErrorResponse && error.status === 404)
  );
}
