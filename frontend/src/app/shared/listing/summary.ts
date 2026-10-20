import { ImageVariantView, PublicListingSummary } from '../../core/api';

/**
 * What the results views read off a listing summary, in one place, so the row and the
 * card cannot disagree about a listing's heading, picture, place or link.
 */

/** The image variant a result shows — 800px on its long edge, see `ImageProperties`. */
const RESULT_VARIANT = 'CARD';
const FALLBACK_VARIANT = 'THUMBNAIL';

/** A result's heading: what the vehicle is, then which version of it. */
export interface Headline {
  readonly title: string;
  readonly subtitle: string | null;
}

/**
 * "Renault Captur" over "E-Tech Plug-in 160 Intens".
 *
 * <p>Make and model when the listing has a make, with the version as the subtitle.
 * Without a make — an older listing, or one typed by hand — the seller's title stands in,
 * so a result is never headed by nothing.
 */
export function headlineOf(listing: PublicListingSummary): Headline {
  const vehicle = [listing.make, listing.model].filter((part) => !!part?.trim()).join(' ');
  if (!listing.make?.trim() || !vehicle) {
    return { title: listing.title ?? '', subtitle: null };
  }
  return { title: vehicle, subtitle: listing.version?.trim() || null };
}

/** The route to a listing's page, or `null` for a listing with no id to link to. */
export function listingLink(id: string | undefined): string[] | null {
  return id ? ['/annonces', id] : null;
}

/**
 * The image a list shows for a listing, or `null` when it has none.
 *
 */
export function coverOf(listing: PublicListingSummary): ImageVariantView | null {
  const cover = listing.coverImage ?? {};
  const variant = cover[RESULT_VARIANT] ?? cover[FALLBACK_VARIANT];
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
