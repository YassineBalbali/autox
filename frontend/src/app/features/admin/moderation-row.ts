import { ChangeDetectionStrategy, Component, computed, input, output, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ListingStatus, ModeratedListing, ModerationView } from '../../core/api';
import { formatDateFr } from '../../shared/format/date';
import { SELLER_ROLE_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { MODERATION_STATUS_LABELS } from '../../shared/listing/moderation.fr';
import { Price, formatPrice } from '../../shared/listing/price';
import { vehicleName } from '../../shared/listing/vehicle-name';
import { STATUS_LABELS } from '../seller/listing-status';
import { ModerationDecision, ModerationDecisionControls } from './moderation-decision';

/** Which date a list is about, and how a row says it. */
const DATE_OF: Readonly<Record<ModerationView, { label: string; field: 'createdAt' | 'publishedAt' | 'updatedAt' }>> = {
  [ModerationView.PENDING]: { label: 'Déposée le', field: 'createdAt' },
  [ModerationView.LIVE]: { label: 'En ligne depuis le', field: 'publishedAt' },
  [ModerationView.REMOVED]: { label: 'Retirée le', field: 'updatedAt' },
};

/**
 * One listing on one line, for finding it among many: its photo, its name and price, who sells
 * it, what is particular about it, where it stands, and the same decisions as on a card. What it
 * leaves out — the description, every photo, the trail — is on the card, and the title opens
 * the listing itself.
 */
@Component({
  selector: 'app-moderation-row',
  imports: [RouterLink, Price, ModerationDecisionControls],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="mrow" [class.mrow--deciding]="decision()?.formOpen()" [attr.data-listing]="listing().id" [attr.aria-busy]="busy()">
      <div class="mrow__thumb">
        @if (thumbnail(); as src) {
          <img [src]="src" alt="" loading="lazy" decoding="async" width="96" height="72" />
        }
      </div>

      <div class="mrow__main">
        <h2 class="mrow__title">
          @if (online()) {
            <a [routerLink]="['/annonces', listing().id]" target="_blank" rel="noopener">{{ listing().title }}</a>
          } @else {
            {{ listing().title }}
          }
        </h2>
        <p class="mrow__vehicle meta">{{ vehicle() }}</p>
        <p class="mrow__seller">
          <span class="mrow__name">{{ seller().displayName }}</span>
          @if (sellerKind()) {
            <span class="meta">· {{ sellerKind() }}</span>
          }
          <span class="meta mrow__email">{{ seller().email }}</span>
        </p>
        @if (item().editedSinceReview || listing().damage || leasing() || item().demo) {
          <p class="mrow__badges">
            @if (item().editedSinceReview) {
              <span class="tag" data-badge="edited">Modifiée</span>
            }
            @if (listing().damage) {
              <span class="tag tag--danger" data-badge="damaged">Endommagé</span>
            }
            @if (leasing()) {
              <span class="tag tag--accent" data-badge="leasing">Leasing</span>
            }
            @if (item().demo) {
              <span class="tag mrow__demo" data-badge="demo">Démo</span>
            }
          </p>
        }
      </div>

      <!-- One line under the listing on a phone; two columns of the row from 1024px. -->
      <div class="mrow__facts">
        <div class="mrow__price">
          <!-- A leasing offer by its monthly payment, as the public results show it: its cash
               price is not what the seller asked anyone to pay. -->
          @if (monthly(); as monthly) {
            <span class="mrow__monthly" data-price="monthly">{{ monthly }}<span class="mrow__unit">/mois</span></span>
          } @else if (listing().priceAmount !== undefined) {
            <app-price [amountMinor]="listing().priceAmount!" [currency]="listing().currencyCode ?? 'EUR'" [minorUnit]="minorUnit()" />
          }
        </div>

        <div class="mrow__state">
          <span class="tag" [class.tag--accent]="online()" data-tag="status">{{ statusLabel() }}</span>
          <span class="meta" data-tag="verdict">{{ verdictLabel() }}</span>
          @if (date()) {
            <span class="meta mrow__date">{{ date() }}</span>
          }
        </div>
      </div>

      <app-moderation-decision
        class="mrow__decision"
        [item]="item()"
        [busy]="busy()"
        [failure]="failure()"
        [compact]="true"
        (decided)="decided.emit($event)"
      />
    </article>
  `,
  styleUrl: './moderation-row.scss',
})
export class ModerationRow {
  readonly item = input.required<ModeratedListing>();
  /** The list the row is on, which says which of its dates matters. */
  readonly view = input.required<ModerationView>();
  readonly busy = input(false);
  readonly failure = input<string | null>(null);
  readonly minorUnit = input(2);

  readonly decided = output<ModerationDecision>();

  protected readonly decision = viewChild(ModerationDecisionControls);

  protected readonly listing = computed(() => this.item().listing ?? {});
  protected readonly seller = computed(() => this.item().seller ?? {});
  protected readonly online = computed(() => this.listing().status === ListingStatus.ACTIVE);
  protected readonly leasing = computed(() => !!this.listing().leasing);
  protected readonly monthly = computed(() => {
    const payment = this.listing().leasing?.monthlyPayment;
    return payment === undefined ? null : formatPrice(payment, this.listing().currencyCode ?? 'EUR', this.minorUnit());
  });

  protected readonly thumbnail = computed(() => {
    const first = this.listing().images?.[0]?.variants;
    return first?.['THUMBNAIL']?.url ?? first?.['CARD']?.url ?? null;
  });
  protected readonly vehicle = computed(() => {
    const vehicle = this.listing().vehicle;
    return [vehicleName(vehicle?.make, vehicle?.model), vehicle?.version ?? vehicle?.trim ?? ''].filter(Boolean).join(' ');
  });
  protected readonly sellerKind = computed(() => labelOf(SELLER_ROLE_LABELS, this.seller().role));
  protected readonly statusLabel = computed(() => labelOf(STATUS_LABELS, this.listing().status));
  protected readonly verdictLabel = computed(() => labelOf(MODERATION_STATUS_LABELS, this.listing().moderationStatus));
  protected readonly date = computed(() => {
    const which = DATE_OF[this.view()];
    const day = formatDateFr(this.listing()[which.field]);
    return day ? `${which.label} ${day}` : null;
  });
}
