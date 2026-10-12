import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PublicListingSummary } from '../../core/api';
import { coverOf, listingLink, placeOf } from './listing-row';
import { Price } from './price';
import { SpecLine } from './spec-line';

/**
 * One vehicle as a card: photo on top, then price, title, the spec line, and who is
 * selling it from where.
 *
 * <p>The alternative to {@link ListingRow} for the same result, for a buyer who chooses by
 * looking rather than by comparing. It is deliberately the same information in a
 * different arrangement — same price component, same spec line, same picture — so that
 * switching views changes how the list is laid out and nothing about what it says.
 *
 * <p>The price comes first under the photo, above the title. In a column of rows the price
 * has its own place on the right; in a card there is no right, so it takes the next most
 * prominent position, and it is in that position on every card.
 */
@Component({
  selector: 'app-listing-card',
  imports: [Price, SpecLine, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="card">
      <div class="card__photo">
        @if (photo(); as image) {
          <img
            class="card__img"
            [src]="image.url"
            [attr.width]="image.width"
            [attr.height]="image.height"
            [attr.loading]="eager() ? 'eager' : 'lazy'"
            decoding="async"
            [alt]="listing().title"
            (error)="photoBroken.set(true)"
          />
        } @else {
          <span class="card__nophoto" i18n="@@listingNoPhoto">Pas de photo</span>
        }
      </div>

      <div class="card__body">
        @if (listing().priceAmount !== undefined) {
          <app-price
            [amountMinor]="listing().priceAmount!"
            [currency]="listing().currencyCode ?? 'EUR'"
            [minorUnit]="minorUnit()"
          />
        }

        <h2 class="card__title">
          @if (link(); as target) {
            <a class="card__link" [routerLink]="target">{{ listing().title }}</a>
          } @else {
            {{ listing().title }}
          }
        </h2>

        <app-spec-line
          [year]="listing().firstRegistrationYear"
          [mileage]="listing().mileageKm"
          [fuel]="fuelLabel()"
          [gearbox]="gearboxLabel()"
        />

        <p class="card__seller">
          @if (isProfessional()) {
            <span class="tag" i18n="@@listingPro">Pro</span>
          }
          @if (sellerName()) {
            <span class="card__name">{{ sellerName() }}</span>
          }
          @if (place()) {
            <span class="card__place">{{ place() }}</span>
          }
        </p>
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
      border: var(--border);
      border-radius: var(--r-md);
      overflow: hidden;

      &:hover {
        border-color: var(--c-ink-faint);
      }
    }

    .card__photo {
      aspect-ratio: 4 / 3;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--c-surface-sunk);
      border-bottom: var(--divider);
    }

    .card__img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .card__nophoto {
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
    }

    .card__body {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: var(--s-1);
      padding: var(--s-2) var(--s-3) var(--s-3);
    }

    .card__title {
      font-size: var(--t-body);
      font-weight: var(--fw-bold);
      line-height: var(--lh-snug);
      // Two lines, then cut, so cards in a row keep their text on the same baselines.
      display: -webkit-box;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
      overflow: hidden;
    }

    // The title is the link; its ::after covers the card, so the card is the target.
    .card__link {
      color: inherit;
      text-decoration: none;

      &::after {
        content: '';
        position: absolute;
        inset: 0;
      }

      &:hover {
        color: var(--c-accent);
      }

      &:focus-visible {
        outline: none;

        &::after {
          outline: 2px solid var(--c-focus);
          outline-offset: -2px;
        }
      }
    }

    // Pinned to the foot of the card, whatever the title's length.
    .card__seller {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0 var(--s-1);
      margin-top: auto;
      padding-top: var(--s-1);
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
      line-height: var(--lh-snug);
    }

    .card__name {
      color: var(--c-ink-soft);
      font-weight: var(--fw-semibold);
    }

    .card__name + .card__place::before {
      content: '·';
      margin-right: var(--s-1);
    }
  `,
})
export class ListingCard {
  readonly listing = input.required<PublicListingSummary>();
  readonly minorUnit = input(2);
  readonly fuelLabel = input<string | null>(null);
  readonly gearboxLabel = input<string | null>(null);
  readonly eager = input(false);

  protected readonly photoBroken = signal(false);

  protected readonly photo = computed(() => (this.photoBroken() ? null : coverOf(this.listing())));
  protected readonly place = computed(() => placeOf(this.listing()));
  protected readonly sellerName = computed(() => this.listing().seller?.displayName ?? null);
  protected readonly isProfessional = computed(
    () => this.listing().seller?.role === 'PROFESSIONNEL',
  );
  protected readonly link = computed(() => listingLink(this.listing().id));
}
