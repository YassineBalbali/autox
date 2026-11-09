import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { AdminService, ReportedListing } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { LISTING_REPORT_REASONS } from '../../shared/listing/listing-report.fr';
import { formatPrice } from '../../shared/listing/price';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { Alert } from '../../shared/ui/alert';
import { sentAtFull } from '../messages/messaging';

const PAGE_SIZE = 10;


/**
 * Reported listings, on the Signalements page above the reported messages: the reason, the
 * note, who reported it, and the listing as it is now. Two decisions, as for a message:
 * dismiss, which leaves the listing online, or take it down, which records a moderation decision
 * with the report's reason. Either closes every open report on that listing.
 */
@Component({
  selector: 'app-reported-listings',
  imports: [RouterLink, Alert, SellerBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // The same cards as the reported messages beside them.
  styleUrls: ['./moderation-page.scss', './reported-messages-page.scss'],
  template: `
    <section class="stack" aria-labelledby="annonces-signalees">
      <h2 id="annonces-signalees" class="report-section__title">
        Annonces signalées
        @if (state() === 'ready' && total() > 0) {
          <span class="meta" data-count="listings">{{ total() }} en attente</span>
        }
      </h2>

      @if (notice()) {
        <app-alert kind="success">{{ notice() }}</app-alert>
      }

      @switch (state()) {
        @case ('loading') {
          <div class="panel moderation__state" aria-busy="true"><p class="meta">Chargement des annonces signalées…</p></div>
        }
        @case ('error') {
          <div class="panel stack">
            <app-alert kind="error">{{ loadFailure() }}</app-alert>
            <div class="form-actions">
              <button class="btn btn--quiet" type="button" (click)="load()">Réessayer</button>
            </div>
          </div>
        }
        @case ('ready') {
          @if (items().length === 0) {
            <div class="panel moderation__state"><p data-empty="listings">Aucune annonce signalée en attente.</p></div>
          } @else {
            @for (item of items(); track item.reportId) {
              <article class="panel report-card" data-report="listing" [attr.aria-labelledby]="'signalement-annonce-' + item.reportId">
                <header class="report-card__head">
                  <h3 class="report-card__reason" [id]="'signalement-annonce-' + item.reportId">{{ reasonLabel(item) }}</h3>
                  <p class="meta">
                    Signalée par <strong>{{ item.reporter.displayName }}</strong> le
                    <time [attr.datetime]="item.reportedAt">{{ sentAtFull(item.reportedAt) }}</time>
                    @if (item.openOnListing > 1) {
                      · <strong data-others>{{ item.openOnListing }} signalements</strong> sur cette annonce
                    }
                  </p>
                  <p class="meta report-card__listing">
                    @if (item.listingStatus === 'ACTIVE') {
                      <a [routerLink]="['/annonces', item.listingId]">{{ item.title }}</a>
                    } @else {
                      {{ item.title }} <span class="tag">Plus en ligne</span>
                    }
                    · {{ price(item) }} · {{ item.seller.displayName }}
                    <app-seller-badge [role]="item.seller.role" />
                  </p>
                </header>

                @if (item.note) {
                  <blockquote class="report-card__note">
                    <span class="visually-hidden">Précision du signalement :</span>
                    {{ item.note }}
                  </blockquote>
                }

                @if (failures()[item.reportId]) {
                  <app-alert kind="error">{{ failures()[item.reportId] }}</app-alert>
                }

                <div class="form-actions">
                  @if (item.listingStatus === 'ACTIVE') {
                    <button class="btn btn--danger" type="button" data-action="remove-listing"
                            [disabled]="busy() !== null" (click)="remove(item)">
                      Retirer l’annonce
                    </button>
                  }
                  <button class="btn btn--quiet" type="button" data-action="dismiss-listing"
                          [disabled]="busy() !== null" (click)="dismiss(item)">
                    Classer sans suite
                  </button>
                </div>
              </article>
            }

            @if (totalPages() > 1) {
              <nav class="moderation__pages" aria-label="Pagination des annonces signalées">
                <button class="btn btn--quiet" type="button" [disabled]="page() === 0" (click)="goTo(page() - 1)">Précédente</button>
                <span class="meta">{{ page() + 1 }} / {{ totalPages() }}</span>
                <button class="btn btn--quiet" type="button" [disabled]="!hasNext()" (click)="goTo(page() + 1)">Suivante</button>
              </nav>
            }
          }
        }
      }
    </section>
  `,
})
export class ReportedListings {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);

  /** After a decision, so the page can recount. */
  readonly decided = output<void>();

  protected readonly sentAtFull = sentAtFull;

  protected readonly items = signal<ReportedListing[]>([]);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly failures = signal<Readonly<Record<string, string>>>({});
  protected readonly hasNext = computed(() => this.page() + 1 < this.totalPages());

  constructor() {
    this.load();
  }

  protected reasonLabel(item: ReportedListing): string {
    return LISTING_REPORT_REASONS.find((option) => option.reason === item.reason)?.label ?? '';
  }

  /** As the public results show it: the monthly payment for a leasing offer. */
  protected price(item: ReportedListing): string {
    return item.leasingMonthly != null
      ? `${formatPrice(item.leasingMonthly, item.currencyCode)}/mois`
      : formatPrice(item.priceAmount, item.currencyCode);
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.api.openListingReports({ page: this.page(), size: PAGE_SIZE }).subscribe({
      next: (page) => {
        const items = page.items ?? [];
        if (items.length === 0 && this.page() > 0) {
          this.goTo(this.page() - 1);
          return;
        }
        this.items.set(items);
        this.total.set(page.totalElements ?? 0);
        this.totalPages.set(page.totalPages ?? 0);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.loadFailure.set(this.errors.translate(error).message);
        this.state.set('error');
      },
    });
  }

  protected remove(item: ReportedListing): void {
    this.decide(item, (id) => this.api.removeReportedListing({ id }), 'Annonce retirée. Le vendeur voit le motif dans ses annonces.');
  }

  protected dismiss(item: ReportedListing): void {
    this.decide(item, (id) => this.api.dismissListingReport({ id }), 'Signalement classé sans suite. L’annonce reste en ligne.');
  }

  private decide(item: ReportedListing, call: (id: string) => Observable<unknown>, done: string): void {
    const id = item.reportId;
    if (this.busy() !== null) {
      return;
    }
    this.busy.set(id);
    this.notice.set(null);
    this.failures.update(({ [id]: _, ...rest }) => rest);
    call(id).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(done);
        this.decided.emit();
        this.load();
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.failures.update((failures) => ({ ...failures, [id]: this.errors.translate(error).message }));
      },
    });
  }
}
