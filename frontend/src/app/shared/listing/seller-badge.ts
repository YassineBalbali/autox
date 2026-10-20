import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { Icon } from '../ui/icon';

/**
 * Who is selling, as a badge: a professional or a private seller.
 *
 * <p>Both are shown, not only "Pro": a buyer weighs a dealer's warranty against a private
 * sale's price, and an absent badge says nothing either way. A seller with no known role
 * gets no badge rather than a guess.
 */
@Component({
  selector: 'app-seller-badge',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @switch (kind()) {
      @case ('pro') {
        <span class="badge badge--pro">
          <app-icon name="store" [size]="13" [strokeWidth]="2" />
          <ng-container i18n="@@listingPro">Pro</ng-container>
        </span>
      }
      @case ('private') {
        <span class="badge">
          <app-icon name="user" [size]="13" [strokeWidth]="2" />
          <ng-container i18n="@@listingPrivate">Particulier</ng-container>
        </span>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .badge {
      display: inline-flex;
      align-items: center;
      gap: var(--s-1);
      height: 1.375rem;
      padding: 0 var(--s-2);
      border-radius: var(--r-sm);
      background: var(--c-surface-sunk);
      border: 1px solid var(--c-line);
      color: var(--c-ink-soft);
      font-size: var(--t-2xs);
      font-weight: var(--fw-bold);
      letter-spacing: 0.04em;
      text-transform: uppercase;
      white-space: nowrap;
    }

    .badge--pro {
      background: var(--c-accent-soft);
      border-color: transparent;
      color: var(--c-accent-strong);
    }
  `,
})
export class SellerBadge {
  /** `SellerCard.role`, as the API sends it. */
  readonly role = input<string | null | undefined>();

  protected readonly kind = computed(() => {
    switch (this.role()) {
      case 'PROFESSIONNEL':
        return 'pro';
      case 'PARTICULIER':
        return 'private';
      default:
        return null;
    }
  });
}
