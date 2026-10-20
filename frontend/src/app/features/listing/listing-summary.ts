import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { PublicListingDetail } from '../../core/api';
import { formatDateFr } from '../../shared/format/date';
import { Price } from '../../shared/listing/price';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { departmentOf } from '../../shared/listing/summary';
import { Icon } from '../../shared/ui/icon';

const MONTH_AND_YEAR = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' });

/**
 * The right-hand panel of a listing: what it is, what it costs, who sells it, and the
 * button to reach them — everything needed to decide, and to act, in one card that
 * follows the page down.
 *
 * <p>One card rather than a price panel and a seller panel stacked: the seller is part
 * of the decision (a dealer's warranty, a private sale's price), and the contact button
 * belongs with the person it contacts.
 */
@Component({
  selector: 'app-listing-summary',
  imports: [Price, SellerBadge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './listing-summary.html',
  styleUrl: './listing-summary.scss',
})
export class ListingSummary {
  readonly listing = input.required<PublicListingDetail>();
  /** Decimal places of the listing's currency. */
  readonly minorUnit = input(2);

  /** "Lyon (69)" */
  protected readonly place = computed(() => {
    const location = this.listing().location;
    if (!location?.city) {
      return null;
    }
    const department = departmentOf(location.postalCode, location.countryCode);
    return department ? `${location.city} (${department})` : location.city;
  });

  /** "mars 2026" */
  protected readonly memberSince = computed(() => {
    const since = this.listing().seller?.memberSince;
    const date = since ? new Date(since) : null;
    return date && !Number.isNaN(date.getTime()) ? MONTH_AND_YEAR.format(date) : null;
  });

  /** "12 octobre 2026" */
  protected readonly published = computed(() => formatDateFr(this.listing().publishedAt));

  protected readonly isProfessional = computed(() => this.listing().seller?.role === 'PROFESSIONNEL');
}
