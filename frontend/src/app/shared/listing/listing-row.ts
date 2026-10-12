import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ImageVariantView, PublicListingSummary } from '../../core/api';
import { formatDateFr } from '../format/date';
import { Price } from './price';
import { SpecLine } from './spec-line';

/** The image variant a results row shows — 800px on its long edge, see `ImageProperties`. */
const ROW_VARIANT = 'CARD';
const FALLBACK_VARIANT = 'THUMBNAIL';

/**
 * One vehicle in a list of results: photo on the left, the facts in the middle, the price
 * on the right.
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
 */
@Component({
  selector: 'app-listing-row',
  imports: [Price, SpecLine, RouterLink],
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

  /** Set when the image failed to load, so the row falls back to the placeholder. */
  protected readonly photoBroken = signal(false);

  protected readonly photo = computed(() =>
    this.photoBroken() ? null : coverOf(this.listing()),
  );

  protected readonly isProfessional = computed(
    () => this.listing().seller?.role === 'PROFESSIONNEL',
  );

  protected readonly place = computed(() => placeOf(this.listing()));

  protected readonly published = computed(() => formatDateFr(this.listing().publishedAt));

  protected readonly link = computed(() => listingLink(this.listing().id));
}

/** The route to a listing's page, or `null` for a listing with no id to link to. */
export function listingLink(id: string | undefined): string[] | null {
  return id ? ['/annonces', id] : null;
}

/**
 * The image a list shows for a listing, or `null` when it has none.
 *
 * <p>Shared by the row and the card, so the two views of the same result cannot pick
 * different pictures.
 */
export function coverOf(listing: PublicListingSummary): ImageVariantView | null {
  const cover = listing.coverImage ?? {};
  const variant = cover[ROW_VARIANT] ?? cover[FALLBACK_VARIANT];
  return variant?.url ? variant : null;
}

/** "Lyon (69)" — the city, with the département when the postal code gives one. */
export function placeOf(listing: PublicListingSummary): string | null {
  const { city, postalCode, countryCode } = listing;
  if (!city) {
    return null;
  }
  const department = departmentOf(postalCode, countryCode);
  return department ? `${city} (${department})` : city;
}

/**
 * The French département from a postal code: its first two digits, or three overseas.
 *
 * <p>France only, and only for a well-formed code. Applied to another country's postal
 * code this would print a number that means nothing there, so anything else returns
 * `null` and the row shows the city alone.
 */
export function departmentOf(
  postalCode: string | null | undefined,
  countryCode: string | null | undefined,
): string | null {
  if (countryCode !== 'FR' || !postalCode || !/^\d{5}$/.test(postalCode)) {
    return null;
  }
  // 97x and 98x are the overseas departments and collectivities, which use three digits.
  return /^9[78]/.test(postalCode) ? postalCode.slice(0, 3) : postalCode.slice(0, 2);
}
