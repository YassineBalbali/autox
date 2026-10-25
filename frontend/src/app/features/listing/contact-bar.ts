import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { PublicListingDetail } from '../../core/api';
import { Price } from '../../shared/listing/price';
import { PriceBadge } from '../../shared/listing/price-badge';
import { Icon } from '../../shared/ui/icon';

/**
 * The phone's bottom bar on a listing: the price, its verdict, and the way to the seller,
 * always within the thumb's reach however far down the page the buyer has read.
 *
 * <p>Below the large breakpoint only. From there up the summary is a sticky column beside
 * the photographs and already does this.
 *
 * <p>It owns no contact logic. Its button asks the page, which asks the contact block in
 * the summary to do what its own button would ({@link ContactSeller.engage}), so the two
 * can never disagree about signing in, writing, or a message already sent.
 */
@Component({
  selector: 'app-contact-bar',
  imports: [Price, PriceBadge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contact-bar' },
  template: `
    <div class="bar">
      <div class="bar__price">
        @if (listing().priceAmount !== undefined) {
          <app-price
            size="result"
            [amountMinor]="listing().priceAmount!"
            [currency]="listing().currencyCode ?? 'EUR'"
            [minorUnit]="minorUnit()"
          />
        }
        <app-price-badge class="bar__verdict" [indicator]="listing().priceIndicator" />
      </div>
      <button class="btn btn--primary bar__contact" type="button" (click)="contact.emit()">
        <app-icon name="message-circle" [size]="18" [strokeWidth]="2" />
        <span i18n="@@contactBarButton">Contacter</span>
      </button>
    </div>
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    :host {
      position: fixed;
      right: 0;
      bottom: 0;
      left: 0;
      z-index: 30;
      background: var(--c-surface);
      border-top: var(--divider);
      box-shadow: 0 -2px 12px rgb(11 31 58 / 10%);

      @media (min-width: t.$bp-lg) {
        display: none;
      }
    }

    .bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--s-3);
      padding: var(--s-2) var(--gutter) calc(var(--s-2) + env(safe-area-inset-bottom));
    }

    .bar__price {
      display: flex;
      flex-direction: column;
      gap: var(--s-0);
      min-width: 0;
      line-height: var(--lh-tight);
    }

    .bar__verdict:empty {
      display: none;
    }

    .bar__contact {
      flex: none;
      height: 48px;
      padding-inline: var(--s-5);
      font-size: var(--t-md);
    }
  `,
})
export class ContactBar {
  readonly listing = input.required<PublicListingDetail>();
  /** Decimal places of the listing's currency. */
  readonly minorUnit = input(2);

  readonly contact = output<void>();
}
