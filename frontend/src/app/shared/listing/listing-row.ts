import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PublicListingSummary } from '../../core/api';
import { formatDateFr } from '../format/date';
import { Icon } from '../ui/icon';
import { ListingThumb } from './listing-thumb';
import { Price } from './price';
import { PriceBadge } from './price-badge';
import { SellerBadge } from './seller-badge';
import { SpecFacts } from './spec-facts';
import { SpecLine } from './spec-line';
import { headlineOf, listingLink, placeOf } from './summary';

/**
 * One vehicle in a list of results: photo on the left, the facts in the middle, the price
 * on the right.
 *
 * <p>The heading is the make and model, with the version under it, rather than the
 * seller's own title: "Renault Captur" first, in the same words on every Captur, is what a
 * buyer scans a column for. The seller's title is on the listing's page.
 *
 * <p>A full-width row, not a card. A results page is a comparison, and a comparison is
 * read down a column: with every price at the same x and every spec line starting at the
 * same x, forty vehicles can be scanned in the time a card grid shows nine.
 *
 * <p>The fuel and gearbox labels are passed in already translated. The row formats
 * numbers and dates; it does not own a vocabulary, so it can be reused by a screen with
 * different words — a dealer's stock list, a saved search — without change.
 *
 * <p>The title is the link to the listing, and the whole row is its target: the link's
 * hit area is stretched over the row in CSS. One link per row, not a link wrapped round
 * everything — a screen reader then announces "Peugeot 308, lien" rather than reading
 * the price, the mileage and the town out as one enormous link name.
 *
 * <p>On a phone (below the small breakpoint) the row tightens to what fits beside a
 * photo: the price and its verdict, the title, two short spec lines — year and mileage,
 * fuel and gearbox — and the town. The version, the power, the seller and the date are on
 * the listing's page. Both versions are in the markup; the stylesheet shows one.
 */
@Component({
  selector: 'app-listing-row',
  imports: [Price, PriceBadge, SpecFacts, SpecLine, SellerBadge, ListingThumb, Icon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './listing-row.html',
  styleUrl: './listing-row.scss',
})
export class ListingRow {
  readonly listing = input.required<PublicListingSummary>();

  /** Decimal places of the listing's currency. */
  readonly minorUnit = input(2);

  readonly fuelLabel = input<string | null>(null);
  readonly gearboxLabel = input<string | null>(null);

  /**
   * Load the photo immediately instead of lazily. For the first few rows, which are on
   * screen at once: lazy-loading those delays the very images the user is looking at.
   */
  readonly eager = input(false);

  protected readonly headline = computed(() => headlineOf(this.listing()));

  protected readonly electric = computed(() => this.listing().fuelType === 'ELECTRIC');

  protected readonly place = computed(() => placeOf(this.listing()));

  protected readonly published = computed(() => formatDateFr(this.listing().publishedAt));

  protected readonly link = computed(() => listingLink(this.listing().id));
}
