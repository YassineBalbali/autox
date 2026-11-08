import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ListingStatus, ModeratedListing, PublicListingDetail } from '../../core/api';
import { formatDateFr, formatDateTimeFr } from '../../shared/format/date';
import { FUEL_LABELS, GEARBOX_LABELS, SELLER_ROLE_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { MODERATION_ACTION_LABELS, MODERATION_REASON_LABELS, MODERATION_STATUS_LABELS } from '../../shared/listing/moderation.fr';
import { DamageNotice } from '../../shared/listing/damage-notice';
import { LeasingOffer } from '../../shared/listing/leasing-offer';
import { PhotoGallery } from '../../shared/listing/photo-gallery';
import { Price } from '../../shared/listing/price';
import { SpecLine } from '../../shared/listing/spec-line';
import { Alert } from '../../shared/ui/alert';
import { galleryImages, specificationRows } from '../listing/listing-view';
import { STATUS_LABELS } from '../seller/listing-status';
import { ModerationDecision, ModerationDecisionControls } from './moderation-decision';

/** One line of the trail, ready to print. */
interface TrailLine {
  readonly id: string;
  readonly when: string;
  readonly what: string;
  readonly who: string;
  readonly reason: string | null;
  readonly note: string | null;
}

/**
 * One listing, as a moderator judges it.
 *
 * <p>Everything needed to decide is on the card: every photo, the whole description, the
 * figures a buyer would check, who is selling, and what has already been decided about it
 * and by whom. A moderator who has to open another page to read the description will stop
 * reading descriptions.
 *
 * <p>The decision itself is {@link ModerationDecisionControls}, the same on a row of the list.
 */
@Component({
  selector: 'app-moderation-card',
  imports: [RouterLink, DamageNotice, LeasingOffer, PhotoGallery, Price, SpecLine, Alert, ModerationDecisionControls],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './moderation-card.html',
  styleUrl: './moderation-card.scss',
})
export class ModerationCard {
  readonly item = input.required<ModeratedListing>();
  /** True while a decision on this listing is on its way to the API. */
  readonly busy = input(false);
  /** Why the last decision on this listing was refused, in French. */
  readonly failure = input<string | null>(null);
  readonly minorUnit = input(2);

  readonly decided = output<ModerationDecision>();

  protected readonly listing = computed(() => this.item().listing ?? {});
  protected readonly seller = computed(() => this.item().seller ?? {});

  protected readonly online = computed(() => this.listing().status === ListingStatus.ACTIVE);
  protected readonly statusLabel = computed(() => labelOf(STATUS_LABELS, this.listing().status));
  protected readonly verdictLabel = computed(() => labelOf(MODERATION_STATUS_LABELS, this.listing().moderationStatus));

  // The moderator's view carries the same fields the public page displays, and more.
  private readonly asDetail = computed(() => this.listing() as unknown as PublicListingDetail);
  protected readonly images = computed(() => galleryImages(this.asDetail()));
  protected readonly specs = computed(() => specificationRows(this.asDetail()));
  protected readonly fuel = computed(() => labelOf(FUEL_LABELS, this.listing().vehicle?.fuelType));
  protected readonly gearbox = computed(() => labelOf(GEARBOX_LABELS, this.listing().vehicle?.gearbox));
  protected readonly place = computed(() =>
    [this.listing().location?.postalCode, this.listing().location?.city].filter(Boolean).join(' '),
  );
  protected readonly publishedOn = computed(() => formatDateFr(this.listing().publishedAt));
  protected readonly sellerKind = computed(() => labelOf(SELLER_ROLE_LABELS, this.seller().role));
  protected readonly memberSince = computed(() => formatDateFr(this.seller().memberSince));

  protected readonly trail = computed<TrailLine[]>(() =>
    (this.item().trail ?? []).map((entry, index) => ({
      id: entry.id ?? String(index),
      when: formatDateTimeFr(entry.at) ?? '',
      what: labelOf(MODERATION_ACTION_LABELS, entry.action),
      who: entry.moderatorName ?? 'un modérateur',
      reason: entry.reasonCode ? labelOf(MODERATION_REASON_LABELS, entry.reasonCode) : null,
      note: entry.note ?? null,
    })),
  );
}
