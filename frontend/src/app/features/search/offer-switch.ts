import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { OfferType } from '../../core/api';

/**
 * "Acheter / Louer": which of the two searches this is. They never mix, so this is a choice of
 * search rather than one more filter, and it holds no state: the caller owns which one is on.
 */
@Component({
  selector: 'app-offer-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="switch" role="group" aria-label="Acheter ou louer" i18n-aria-label="@@offerSwitchLabel">
      <button
        class="switch__option"
        type="button"
        data-offer="SALE"
        [class.switch__option--on]="offer() === sale"
        [attr.aria-pressed]="offer() === sale"
        (click)="choose(sale)"
        i18n="@@offerBuy"
      >
        Acheter
      </button>
      <button
        class="switch__option"
        type="button"
        data-offer="LEASING"
        [class.switch__option--on]="offer() === leasing"
        [attr.aria-pressed]="offer() === leasing"
        (click)="choose(leasing)"
        i18n="@@offerLease"
      >
        Louer
      </button>
    </div>
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    :host {
      display: inline-block;
    }

    .switch {
      display: inline-flex;
      border: var(--border);
      border-radius: var(--r-md);
      background: var(--c-surface);
      overflow: hidden;
    }

    .switch__option {
      height: var(--h-control);
      padding-inline: var(--s-4);
      border: 0;
      background: none;
      color: var(--c-ink-soft);
      font: inherit;
      font-size: var(--t-sm);
      font-weight: var(--fw-bold);
      cursor: pointer;

      &:hover {
        color: var(--c-ink);
      }

      @include t.touch {
        min-width: 44px;
      }
    }

    .switch__option--on,
    .switch__option--on:hover {
      background: var(--c-navy);
      color: var(--c-accent-ink);
    }
  `,
})
export class OfferSwitch {
  readonly offer = input.required<OfferType>();
  readonly changed = output<OfferType>();

  protected readonly sale = OfferType.SALE;
  protected readonly leasing = OfferType.LEASING;

  protected choose(offer: OfferType): void {
    if (offer !== this.offer()) {
      this.changed.emit(offer);
    }
  }
}
