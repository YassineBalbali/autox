import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PublicListingSummary } from '../../core/api';
import { Icon } from '../ui/icon';
import { ListingThumb } from './listing-thumb';
import { Price } from './price';
import { PriceBadge } from './price-badge';
import { SellerBadge } from './seller-badge';
import { SpecFacts } from './spec-facts';
import { headlineOf, listingLink, placeOf } from './summary';

/**
 * One vehicle as a card: photo on top, then make and model, the version, the facts two
 * by two, the price, and where it is and who sells it.
 *
 * <p>The alternative to {@link ListingRow} for the same result, for a buyer who chooses by
 * looking rather than by comparing. It is deliberately the same information in a
 * different arrangement — same picture, same heading, same facts, same price — so that
 * switching views changes how the list is laid out and nothing about what it says.
 *
 * <p>The price sits under the facts, at the same height on every card in a row: the
 * heading is clamped to its lines and the facts are a fixed grid, so the prices line up
 * across the row and can be compared at a glance.
 */
@Component({
  selector: 'app-listing-card',
  imports: [Price, PriceBadge, SpecFacts, SellerBadge, ListingThumb, Icon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="card">
      <app-listing-thumb
        [listing]="listing()"
        [alt]="listing().title ?? headline().title"
        [eager]="eager()"
      />

      <div class="card__body">
        <!-- A heading of the page's outline: level 2 under the results' title, 3 under a
             section's own heading such as "Annonces similaires". -->
        <h2 class="card__title" [attr.aria-level]="headingLevel() === 2 ? null : headingLevel()">
          @if (link(); as target) {
            <a class="card__link" [routerLink]="target">{{ headline().title }}</a>
          } @else {
            {{ headline().title }}
          }
          <!-- Always present, empty or not, so the facts start at the same height on every card. -->
          <span class="card__version">{{ headline().subtitle }}</span>
        </h2>

        <app-spec-facts
          layout="grid"
          [year]="listing().firstRegistrationYear"
          [mileage]="listing().mileageKm"
          [fuel]="fuelLabel()"
          [electric]="electric()"
          [gearbox]="gearboxLabel()"
        />

        <div class="card__price">
          @if (listing().priceAmount !== undefined) {
            <app-price
              size="result"
              [amountMinor]="listing().priceAmount!"
              [currency]="listing().currencyCode ?? 'EUR'"
              [minorUnit]="minorUnit()"
            />
          }
          <app-price-badge [indicator]="listing().priceIndicator" />
        </div>

        <div class="card__foot">
          @if (place()) {
            <span class="card__place">
              <app-icon name="map-pin" [size]="15" />
              {{ place() }}
            </span>
          }
          <app-seller-badge [role]="listing().seller?.role" />
        </div>
      </div>
    </article>
  `,
  styles: `
    :host {
      display: block;
      // Lets every card in a grid row stretch to the height of the tallest.
      height: 100%;
    }

    .card {
      // The positioning context for the title link's stretched hit area.
      position: relative;
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--c-surface);
      border: 1px solid var(--c-line);
      border-radius: var(--r-lg);
      box-shadow: var(--shadow-raised);
      overflow: hidden;
      transition:
        box-shadow 150ms ease,
        transform 150ms ease;

      &:hover {
        box-shadow: var(--shadow-raised-hover);
        transform: translateY(-2px);
      }

      @media (prefers-reduced-motion: reduce) {
        transition: none;

        &:hover {
          transform: none;
        }
      }
    }

    .card__body {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: var(--s-3);
      padding: var(--s-3) var(--s-4) var(--s-4);
    }

    .card__title {
      display: flex;
      flex-direction: column;
      gap: var(--s-0);
      margin: 0;
      font-size: var(--t-md);
      font-weight: var(--fw-bold);
      line-height: var(--lh-snug);
    }

    .card__version {
      min-height: 1lh;
      overflow: hidden;
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      font-weight: var(--fw-regular);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    // The title is the link; its ::after covers the card, so the card is the target.
    .card__link {
      color: inherit;
      text-decoration: none;

      .card:hover & {
        color: var(--c-accent);
      }

      &::after {
        content: '';
        position: absolute;
        inset: 0;
      }

      &:focus-visible {
        outline: none;

        &::after {
          outline: 2px solid var(--c-focus);
          outline-offset: -2px;
          border-radius: var(--r-lg);
        }
      }
    }

    // Pinned to the foot of the card, whatever is above it; the verdict beside the price.
    .card__price {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--s-1) var(--s-2);
      margin-top: auto;
    }

    .card__foot {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--s-1) var(--s-2);
      padding-top: var(--s-3);
      border-top: var(--divider);
    }

    .card__place {
      display: inline-flex;
      align-items: center;
      gap: var(--s-1);
      min-width: 0;
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
    }
  `,
})
export class ListingCard {
  readonly listing = input.required<PublicListingSummary>();
  readonly minorUnit = input(2);
  readonly fuelLabel = input<string | null>(null);
  readonly gearboxLabel = input<string | null>(null);
  readonly eager = input(false);
  /** Where the title sits in the page's outline; the element stays an h2 for its styles. */
  readonly headingLevel = input(2);

  protected readonly headline = computed(() => headlineOf(this.listing()));
  protected readonly electric = computed(() => this.listing().fuelType === 'ELECTRIC');
  protected readonly place = computed(() => placeOf(this.listing()));
  protected readonly link = computed(() => listingLink(this.listing().id));
}
