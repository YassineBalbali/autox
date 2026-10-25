import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { Icon } from '../ui/icon';
import { Favourites } from './favourites';

/**
 * The heart on a result: marks the listing as a favourite in this browser, or unmarks it.
 *
 * <p>A toggle button, so `aria-pressed` carries the state and the name stays the action
 * it performs. The listing's name is in it: forty buttons all called "Ajouter aux
 * favoris" are forty identical entries in a screen reader's list of controls.
 *
 * <p>It sits on top of a result whose whole surface is a link. It is positioned above that
 * link's stretched hit area, so a click on the heart marks the listing and does not open it.
 */
@Component({
  selector: 'app-favourite-button',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      class="fav"
      type="button"
      [attr.aria-pressed]="saved()"
      [attr.aria-label]="label()"
      [attr.title]="hint()"
      (click)="toggle($event)"
    >
      <app-icon name="heart" [size]="20" [strokeWidth]="2" />
    </button>
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    :host {
      display: block;
      position: relative;
      // Above the stretched link of the result it sits on.
      z-index: 1;
    }

    .fav {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.5rem;
      height: 2.5rem;
      padding: 0;
      border: 0;
      border-radius: 50%;
      background: rgb(255 255 255 / 92%);
      color: var(--c-ink);
      box-shadow: 0 1px 3px rgb(11 31 58 / 25%);
      cursor: pointer;
      transition:
        color 120ms ease,
        transform 120ms ease;

      &:hover {
        color: var(--c-accent);
        transform: scale(1.06);
      }

      &:focus-visible {
        outline: 2px solid var(--c-focus);
        outline-offset: 2px;
      }

      // A finger's size on a phone; the heart inside stays the same.
      @include t.touch {
        width: 44px;
        height: 44px;
      }

      &[aria-pressed='true'] {
        color: var(--c-accent);

        // Filled when marked: the one state a glance has to tell apart from the other.
        ::ng-deep svg {
          fill: currentColor;
        }
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .fav {
        transition: none;

        &:hover {
          transform: none;
        }
      }
    }
  `,
})
export class FavouriteButton {
  private readonly favourites = inject(Favourites);

  readonly listingId = input.required<string>();
  /** What the listing is called, for the button's accessible name. */
  readonly name = input.required<string>();

  protected readonly saved = computed(() => this.favourites.has(this.listingId()));

  protected readonly label = computed(() =>
    this.saved()
      ? `Retirer ${this.name()} des favoris`
      : `Ajouter ${this.name()} aux favoris`,
  );

  protected readonly hint = computed(() =>
    this.saved() ? 'Retirer des favoris' : 'Ajouter aux favoris',
  );

  protected toggle(event: Event): void {
    // The result around the button is a link; this click is not for it.
    event.preventDefault();
    event.stopPropagation();
    this.favourites.toggle(this.listingId());
  }
}
