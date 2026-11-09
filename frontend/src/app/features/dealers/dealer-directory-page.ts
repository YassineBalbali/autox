import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { Subject, catchError, combineLatest, map, of, startWith, switchMap, tap } from 'rxjs';

import { DealerCard, DealerDirectory, DealerSort, DealersService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { PageMeta } from '../../core/ui/page-meta';
import { DealerLogo } from '../../shared/dealer/dealer-logo';
import { departementLabel } from '../../shared/dealer/departements.fr';
import { countFr } from '../../shared/format/plural';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';

/** Cards a page: three rows of the widest grid, and few enough to scroll on a phone. */
export const DIRECTORY_PAGE_SIZE = 24;

/** The directory's state, as the URL holds it. */
export interface DirectoryQuery {
  readonly q: string;
  readonly departement: string;
  readonly ville: string;
  readonly tri: 'annonces' | 'nom';
  /** From 1, as a person counts. */
  readonly page: number;
}

/** The URL's query string, read leniently: anything unexpected is the default. */
export function parseDirectoryQuery(params: ParamMap): DirectoryQuery {
  const page = Number.parseInt(params.get('page') ?? '', 10);
  return {
    q: (params.get('q') ?? '').trim(),
    departement: (params.get('departement') ?? '').trim().toUpperCase(),
    ville: (params.get('ville') ?? '').trim(),
    tri: params.get('tri') === 'nom' ? 'nom' : 'annonces',
    page: Number.isFinite(page) && page > 1 ? page : 1,
  };
}

/** Only what differs from the defaults, so the plain directory is plainly /professionnels. */
export function toDirectoryParams(query: DirectoryQuery): Record<string, string> {
  const params: Record<string, string> = {};
  if (query.q) params['q'] = query.q;
  if (query.departement) params['departement'] = query.departement;
  if (query.ville) params['ville'] = query.ville;
  if (query.tri === 'nom') params['tri'] = 'nom';
  if (query.page > 1) params['page'] = String(query.page);
  return params;
}

/**
 * The directory of professionals, at `/professionnels`: the dealers with something online,
 * searched by name, narrowed to a département or a town, sorted by how much they have online or
 * by name. As on the search, the URL is the only state: a filter navigates, the new URL is read,
 * and the directory is asked for what it says. A shared link opens the same page of the same list.
 */
@Component({
  selector: 'app-dealer-directory-page',
  imports: [RouterLink, Alert, Icon, DealerLogo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dealer-directory-page.html',
  styleUrl: './dealer-directory-page.scss',
})
export class DealerDirectoryPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(DealersService);
  private readonly errors = inject(ErrorMessageService);

  protected readonly query = toSignal(this.route.queryParamMap.pipe(map(parseDirectoryQuery)), {
    initialValue: parseDirectoryQuery(this.route.snapshot.queryParamMap),
  });

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly failure = signal<string | null>(null);
  protected readonly directory = signal<DealerDirectory | null>(null);

  protected readonly cards = computed(() => this.directory()?.items ?? []);
  protected readonly total = computed(() => this.directory()?.totalElements ?? 0);
  protected readonly pages = computed(() => Math.max(1, this.directory()?.totalPages ?? 1));
  protected readonly filtered = computed(() => {
    const query = this.query();
    return !!(query.q || query.departement || query.ville);
  });
  protected readonly countText = computed(() => countFr(this.total(), 'professionnel', 'professionnels'));

  /** The département choices: those with a dealer, and the one asked for even if it has none now. */
  protected readonly departements = computed(() => {
    const listed = (this.directory()?.departements ?? []).map((entry) => ({
      code: entry.code ?? '',
      label: `${departementLabel(entry.code)} (${entry.dealers ?? 0})`,
    }));
    const asked = this.query().departement;
    if (asked && !listed.some((entry) => entry.code === asked)) {
      listed.push({ code: asked, label: departementLabel(asked) });
    }
    return listed;
  });

  /** "Réessayer": the same URL again, which the router would not emit a second time. */
  private readonly retries = new Subject<void>();

  constructor() {
    const meta = inject(PageMeta);
    meta.describe({
      title: 'Annuaire des professionnels · AutoX',
      description:
        'Les garages et concessionnaires qui vendent sur AutoX : leurs coordonnées, leurs horaires et toutes leurs annonces en ligne.',
    });
    inject(DestroyRef).onDestroy(() => meta.reset());

    combineLatest([this.route.queryParamMap, this.retries.pipe(startWith(undefined))])
      .pipe(
        map(([params]) => parseDirectoryQuery(params)),
        tap(() => {
          this.state.set('loading');
          this.failure.set(null);
        }),
        switchMap((query) =>
          this.api
            .dealerDirectory({
              q: query.q || undefined,
              departement: query.departement || undefined,
              city: query.ville || undefined,
              sort: query.tri === 'nom' ? DealerSort.NAME : DealerSort.LISTINGS,
              page: query.page - 1,
              size: DIRECTORY_PAGE_SIZE,
            })
            .pipe(
              map((directory) => ({ directory, error: null as unknown })),
              catchError((error: unknown) => of({ directory: null, error })),
            ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ directory, error }) => {
        if (directory) {
          this.directory.set(directory);
          this.state.set('ready');
        } else {
          this.failure.set(this.errors.messageFor(error));
          this.state.set('error');
        }
      });
  }

  protected search(event: Event): void {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const data = new FormData(form);
    this.navigate({
      ...this.query(),
      q: String(data.get('q') ?? '').trim(),
      ville: String(data.get('ville') ?? '').trim(),
      page: 1,
    });
  }

  protected setDepartement(event: Event): void {
    this.navigate({ ...this.query(), departement: (event.target as HTMLSelectElement).value, page: 1 });
  }

  protected setSort(event: Event): void {
    const tri = (event.target as HTMLSelectElement).value === 'nom' ? 'nom' : 'annonces';
    this.navigate({ ...this.query(), tri, page: 1 });
  }

  protected clear(): void {
    this.navigate({ q: '', departement: '', ville: '', tri: this.query().tri, page: 1 });
  }

  protected goTo(page: number): void {
    this.navigate({ ...this.query(), page });
  }

  protected retry(): void {
    this.retries.next();
  }

  protected place(card: DealerCard): string | null {
    if (!card.city) {
      return null;
    }
    return card.departement ? `${card.city} (${card.departement})` : card.city;
  }

  protected listings(card: DealerCard): string {
    const sale = card.saleListings ?? 0;
    const leasing = card.leasingListings ?? 0;
    const parts = [];
    if (sale > 0 || leasing === 0) {
      parts.push(countFr(sale, 'annonce', 'annonces'));
    }
    if (leasing > 0) {
      parts.push(`${countFr(leasing, 'offre', 'offres')} de location`);
    }
    return parts.join(' · ');
  }

  protected memberSince(card: DealerCard): string | null {
    return memberSinceFr(card.memberSince);
  }

  private navigate(query: DirectoryQuery): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: toDirectoryParams(query) });
  }
}

const MONTH_AND_YEAR = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' });

/** "Membre depuis mars 2021". */
export function memberSinceFr(iso: string | null | undefined): string | null {
  const date = iso ? new Date(iso) : null;
  return date && !Number.isNaN(date.getTime()) ? `Membre depuis ${MONTH_AND_YEAR.format(date)}` : null;
}
