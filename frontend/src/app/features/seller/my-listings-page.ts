import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { ErrorCode, ListingQuota, ListingStatus, ListingSummary, ListingsSellerService } from '../../core/api';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { FlashService } from '../../core/ui/flash.service';
import { formatDateFr } from '../../shared/format/date';
import { formatIntegerFr, NBSP } from '../../shared/format/number';
import { MODERATION_REASON_LABELS } from '../../shared/listing/moderation.fr';
import { Price } from '../../shared/listing/price';
import { Alert } from '../../shared/ui/alert';
import {
  ACTION_LABELS,
  CONFIRMATIONS,
  CONFIRMED_ACTIONS,
  FILTERABLE_STATUSES,
  ListingAction,
  STATUS_LABELS,
  actionsFor,
} from './listing-status';

/** The status filter's values as they appear in the URL (`?statut=`). */
export const STATUS_SLUGS: Partial<Record<ListingStatus, string>> = {
  [ListingStatus.DRAFT]: 'brouillon',
  [ListingStatus.ACTIVE]: 'en-ligne',
  [ListingStatus.PAUSED]: 'en-pause',
  [ListingStatus.SOLD]: 'vendue',
  [ListingStatus.EXPIRED]: 'expiree',
};

/** The status a slug names, or `undefined` for none or for one this page does not know. */
export function statusOfSlug(slug: string | undefined): ListingStatus | undefined {
  return FILTERABLE_STATUSES.find((status) => STATUS_SLUGS[status] === slug);
}

const PAGE_SIZE = 20;

type LoadState = 'loading' | 'ready' | 'error';

/** One row, with everything the template prints already worked out. */
interface Row {
  readonly listing: ListingSummary;
  readonly id: string;
  readonly statusLabel: string;
  readonly online: boolean;
  /** The first photo, small. Null while the listing has none. */
  readonly cover: string | null;
  readonly facts: readonly string[];
  /** "Expire le 12 novembre 2026", "Vendue", … — the date that matters in this state. */
  readonly dateLine: string | null;
  /** Why a moderator took it down, in French. Null for every listing that was not. */
  readonly removal: string | null;
  readonly actions: readonly ListingAction[];
}

/**
 * "Mes annonces": the seller's listings, their state, and what can be done with each.
 *
 * <p>The buttons on a row are the ones the API said it would accept for that listing —
 * see {@link actionsFor} — so a transition that is not valid is not offered. When one is
 * refused anyway (the cap reached in another tab, a listing changed since the list was
 * loaded) the reason is printed in French on the row it concerns, and the list reloaded
 * so the buttons are right again.
 */
@Component({
  selector: 'app-my-listings-page',
  imports: [RouterLink, Alert, Price],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-listings-page.html',
  styleUrl: './my-listings-page.scss',
})
export class MyListingsPage {
  private readonly api = inject(ListingsSellerService);
  private readonly errors = inject(ErrorMessageService);
  private readonly reference = inject(ReferenceStore);

  /** `?statut=` — the filter. The URL is its only home, so it survives a reload. */
  readonly statut = input<string | undefined>();

  protected readonly filters = [
    { slug: null as string | null, label: 'Toutes' },
    ...FILTERABLE_STATUSES.map((status) => ({ slug: STATUS_SLUGS[status]!, label: STATUS_LABELS[status] })),
  ];
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly confirmations = CONFIRMATIONS;

  protected readonly status = computed(() => statusOfSlug(this.statut()));
  protected readonly activeSlug = computed(() => {
    const status = this.status();
    return status ? (STATUS_SLUGS[status] ?? null) : null;
  });

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  private readonly listings = signal<ListingSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);
  private page = 0;

  protected readonly quota = signal<ListingQuota | null>(null);
  /** The message carried over from the screen before — "Votre annonce est en ligne." */
  protected readonly notice = signal<string | null>(inject(FlashService).take());

  /** The listing an action is running on. One at a time. */
  protected readonly busy = signal<string | null>(null);
  /** The action waiting for a yes or a no, and the row it is on. */
  protected readonly confirming = signal<{ id: string; action: ListingAction } | null>(null);
  /** A refused action: which row, why, and whether adding photos is the way out. */
  protected readonly rowFailure = signal<{ id: string; message: string; needsPhoto: boolean } | null>(null);

  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected readonly rows = computed<Row[]>(() =>
    this.listings()
      .filter((listing) => listing.id)
      .map((listing) => ({
        listing,
        id: listing.id!,
        statusLabel: listing.status ? STATUS_LABELS[listing.status] : '',
        online: listing.status === ListingStatus.ACTIVE,
        cover: coverOf(listing),
        facts: factsOf(listing),
        dateLine: dateLineOf(listing),
        removal: listing.removalReason ? (MODERATION_REASON_LABELS[listing.removalReason] ?? null) : null,
        actions: actionsFor(listing),
      })),
  );

  /** "3 annonces en ligne sur 5" — only where there is a cap to speak of. */
  protected readonly quotaLine = computed(() => {
    const quota = this.quota();
    if (!quota || typeof quota.limit !== 'number') {
      return null;
    }
    const active = quota.active ?? 0;
    return `${active} annonce${active > 1 ? 's' : ''} en ligne sur ${quota.limit} possibles avec un compte particulier.`;
  });
  protected readonly capReached = computed(() => this.quota()?.canPublish === false);

  constructor() {
    // A change of filter is a new list.
    effect(() => {
      this.status();
      this.reload();
    });
  }

  protected minorUnit(currency: string | undefined): number {
    return this.minorUnits()[currency ?? 'EUR'] ?? DEFAULT_MINOR_UNIT;
  }

  protected reload(): void {
    this.state.set('loading');
    this.fetch(0, PAGE_SIZE, false);
    this.refreshQuota();
  }

  protected more(): void {
    this.loadingMore.set(true);
    this.fetch(this.page + 1, PAGE_SIZE, true);
  }

  private fetch(page: number, size: number, append: boolean): void {
    this.api.listOwn({ status: this.status(), page, size }).subscribe({
      next: (answer) => {
        const items = answer.items ?? [];
        this.listings.update((current) => (append ? [...current, ...items] : items));
        this.total.set(answer.totalElements ?? items.length);
        this.hasMore.set(answer.hasNext ?? false);
        this.page = append ? page : Math.max(0, Math.ceil(items.length / PAGE_SIZE) - 1);
        this.loadingMore.set(false);
        this.loadFailure.set(null);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.loadingMore.set(false);
        if (append) {
          this.rowFailure.set(null);
          this.loadFailure.set(this.errors.messageFor(error));
        } else {
          this.loadFailure.set(this.errors.messageFor(error));
          this.state.set('error');
        }
      },
    });
  }

  private refreshQuota(): void {
    this.api.quota().subscribe({
      next: (quota) => this.quota.set(quota),
      error: () => this.quota.set(null),
    });
  }

  /** A button on a row. Irreversible ones ask first; the rest act. */
  protected ask(row: Row, action: ListingAction): void {
    this.rowFailure.set(null);
    this.notice.set(null);
    if (CONFIRMED_ACTIONS.has(action)) {
      this.confirming.set({ id: row.id, action });
    } else {
      this.run(row.id, action);
    }
  }

  protected confirm(): void {
    const pending = this.confirming();
    if (pending) {
      this.confirming.set(null);
      this.run(pending.id, pending.action);
    }
  }

  protected cancel(): void {
    this.confirming.set(null);
  }

  private run(id: string, action: ListingAction): void {
    const request = this.requestFor(id, action);
    if (!request) {
      return;
    }
    this.busy.set(id);
    request.subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(DONE[action] ?? null);
        this.refreshInPlace();
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.rowFailure.set({
          id,
          message: this.errors.messageFor(error),
          needsPhoto: asApiError(error)?.code === ErrorCode.PHOTO_REQUIRED,
        });
        // The refusal means this row's buttons were out of date. Fetch the truth.
        this.refreshInPlace();
      },
    });
  }

  private requestFor(id: string, action: ListingAction): Observable<unknown> | null {
    switch (action) {
      case 'publish':
        return this.api.publish({ id });
      case 'pause':
        return this.api.unpublish({ id });
      case 'renew':
        return this.api.renew({ id });
      case 'sold':
        return this.api.markSold({ id });
      case 'delete':
        return this.api._delete({ id });
      case 'edit':
        // A link, not a request.
        return null;
    }
  }

  /** Reloads as many rows as are on screen, without the loading state: nothing jumps. */
  private refreshInPlace(): void {
    const shown = Math.max(PAGE_SIZE, Math.ceil(this.listings().length / PAGE_SIZE) * PAGE_SIZE);
    this.fetch(0, Math.min(shown, 100), false);
    this.refreshQuota();
  }
}

/** What to say once an action has gone through. */
const DONE: Partial<Record<ListingAction, string>> = {
  publish: 'Votre annonce est en ligne.',
  pause: 'Votre annonce est en pause : elle n’est plus visible des acheteurs.',
  renew: 'Votre annonce a été renouvelée.',
  sold: 'Votre annonce est marquée comme vendue.',
  delete: 'Votre annonce a été supprimée.',
};

/** "Peugeot 308 · 2019 · 84 000 km · Lyon" — whichever of those the listing has. */
function factsOf(listing: ListingSummary): string[] {
  const vehicle = [listing.make, listing.model].filter(Boolean).join(' ');
  return [
    vehicle,
    listing.firstRegistrationYear ? String(listing.firstRegistrationYear) : '',
    typeof listing.mileageKm === 'number' ? `${formatIntegerFr(listing.mileageKm)}${NBSP}km` : '',
    listing.city ?? '',
  ].filter(Boolean);
}

/** The smallest size that exists: these are thumbnails, on a list that may hold a hundred. */
function coverOf(listing: ListingSummary): string | null {
  const sizes = listing.coverImage ?? {};
  return sizes['THUMBNAIL']?.url ?? sizes['CARD']?.url ?? sizes['FULL']?.url ?? null;
}

/** The one date that matters for the state the listing is in. */
function dateLineOf(listing: ListingSummary): string | null {
  const expires = formatDateFr(listing.expiresAt);
  const updated = formatDateFr(listing.updatedAt);
  switch (listing.status) {
    case ListingStatus.ACTIVE:
      return expires ? `Expire le ${expires}` : null;
    case ListingStatus.PAUSED:
      return expires ? `En pause · expire le ${expires}` : null;
    case ListingStatus.EXPIRED:
      return expires ? `Expirée le ${expires}` : null;
    default:
      return updated ? `Modifiée le ${updated}` : null;
  }
}
