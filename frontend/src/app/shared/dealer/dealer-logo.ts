import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * A dealer's logo, or their initials on a tile when they have none: the directory's cards and
 * the dealer's page. Decorative either way — the name is always written beside it.
 */
@Component({
  selector: 'app-dealer-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (logoUrl()) {
      <img class="logo__image" [src]="logoUrl()" alt="" loading="lazy" decoding="async" />
    } @else {
      <span class="logo__initials" data-initials>{{ initials() }}</span>
    }
  `,
  host: { class: 'logo', '[class.logo--large]': 'large()', 'aria-hidden': 'true' },
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      width: 56px;
      height: 56px;
      overflow: hidden;
      border: var(--divider);
      border-radius: var(--r-md);
      background: var(--c-surface);
    }

    :host(.logo--large) {
      width: 88px;
      height: 88px;
    }

    .logo__image {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }

    .logo__initials {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      background: var(--c-accent-soft);
      color: var(--c-navy);
      font-size: var(--t-lg);
      font-weight: var(--fw-bold);
      letter-spacing: 0.02em;
    }

    :host(.logo--large) .logo__initials {
      font-size: var(--t-xl);
    }
  `,
})
export class DealerLogo {
  readonly name = input.required<string>();
  readonly logoUrl = input<string | null | undefined>(null);
  readonly large = input(false);

  protected readonly initials = computed(() => initialsOf(this.name()));
}

/**
 * "Garage Bellevue" → "GB", "Nord VO" → "NV", "Autosphère" → "A". The first letter of the first
 * two words that start with one; small words of a name ("du", "de la") are not left out, since
 * "Garage du Port" reads as G D P to nobody and as GD to everybody.
 */
export function initialsOf(name: string): string {
  const letters = name
    .split(/[\s\-–—&'’.]+/)
    .map((word) => word.match(/\p{L}|\p{N}/u)?.[0] ?? '')
    .filter((letter) => letter !== '');
  return letters.slice(0, 2).join('').toUpperCase() || '?';
}
