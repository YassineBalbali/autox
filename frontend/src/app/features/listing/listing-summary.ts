import { ChangeDetectionStrategy, Component, computed, input, viewChild } from '@angular/core';

import { PublicListingDetail } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { formatDateFr } from '../../shared/format/date';
import { Price, formatPrice } from '../../shared/listing/price';
import { PriceBadge } from '../../shared/listing/price-badge';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { departmentOf } from '../../shared/listing/summary';
import { Icon } from '../../shared/ui/icon';
import { ContactSeller } from './contact-seller';

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
  imports: [Price, PriceBadge, SellerBadge, Icon, ContactSeller],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './listing-summary.html',
  styleUrl: './listing-summary.scss',
})
export class ListingSummary {
  readonly listing = input.required<PublicListingDetail>();

  /**
   * The contact block, for the phone's bottom bar to act through and to read. Not
   * required: the page reads it while the summary's own view is still being created.
   */
  readonly contact = viewChild(ContactSeller);
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

  protected readonly isProfessional = computed(
    () => this.listing().seller?.role === 'PROFESSIONNEL',
  );

  /** "Comparé à 7 Peugeot 308 de 2019 à 2023 au kilométrage proche, prix médian 18 490 €." */
  protected readonly basis = computed(() => marketBasis(this.listing(), this.minorUnit()));
}

/**
 * The sentence under the price verdict: how many listings it compares with, which ones,
 * and their median price once each is brought to this car's age and mileage, which is the
 * figure the API returns. Null when the listing has no verdict.
 *
 * <p>The years are the listing's own two either side, the window `MarketPrice` uses on the
 * backend. "De même motorisation" stands for the same fuel and a power within 15 %, and "au
 * kilométrage proche" for the mileage band: both more exact than a buyer needs to read here.
 */
export function marketBasis(listing: PublicListingDetail, minorUnit: number): string | null {
  const indicator = listing.priceIndicator;
  if (
    !indicator?.level ||
    indicator.comparableCount == null ||
    indicator.medianPriceAmount == null
  ) {
    return null;
  }
  const count = indicator.comparableCount;
  const vehicle = [listing.vehicle?.make, listing.vehicle?.model].filter(Boolean).join(' ');
  const year = listing.vehicle?.firstRegistrationYear;
  const years = year == null ? '' : ` de ${year - YEAR_SPAN} à ${year + YEAR_SPAN}`;
  const what = vehicle ? `${count} ${vehicle}` : `${count} annonces comparables`;
  // An estimate, not a price anyone asked: to the euro, since cents would claim a precision
  // the adjustment does not have.
  const unit = 10 ** minorUnit;
  const median = formatPrice(
    Math.round(indicator.medianPriceAmount / unit) * unit,
    listing.currencyCode ?? 'EUR',
    minorUnit,
  );
  return `Comparé à ${what} de même motorisation${years} au kilométrage proche. Prix médian, ramené à l’âge et au kilométrage de cette voiture${NBSP}: ${median}.`;
}

/** `MarketPrice.YEAR_SPAN` on the backend. */
const YEAR_SPAN = 2;
