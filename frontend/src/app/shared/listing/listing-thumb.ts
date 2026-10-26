import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { PublicListingSummary } from '../../core/api';
import { Icon } from '../ui/icon';
import { FavouriteButton } from './favourite-button';
import { coverOf } from './summary';

/**
 * A result's picture: the cover photo in a fixed 4:3 box, how many photos the listing
 * has, and the heart.
 *
 * <p>Shared by the row and the card, so both views of a result show the same picture with
 * the same things on it. The box is 4:3 whether or not there is a photo, so a result
 * without one is the same height as its neighbours and nothing moves when an image
 * arrives.
 */
@Component({
  selector: 'app-listing-thumb',
  imports: [Icon, FavouriteButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="thumb">
      @if (photo(); as image) {
        <img
          class="thumb__img"
          [src]="image.url"
          [attr.width]="image.width"
          [attr.height]="image.height"
          [attr.loading]="eager() ? 'eager' : 'lazy'"
          decoding="async"
          [alt]="alt()"
          (error)="photoBroken.set(true)"
        />
      } @else {
        <span class="thumb__none">
          <app-icon name="image" [size]="28" />
          <span i18n="@@listingNoPhoto">Pas de photo</span>
        </span>
      }

      @if (photoCount() > 0) {
        <span class="thumb__count">
          <app-icon name="camera" [size]="14" [strokeWidth]="2" />
          <span class="thumb__figure" aria-hidden="true">{{ photoCount() }}</span>
          <span class="visually-hidden">{{ countLabel() }}</span>
        </span>
      }

      @if (listing().id; as id) {
        <app-favourite-button class="thumb__fav" [listingId]="id" [name]="alt()" />
      }
    </div>
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    :host {
      display: block;
    }

    .thumb {
      position: relative;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--c-surface-sunk);
    }

    .thumb__img {
      width: 100%;
      height: 100%;
      // Sellers upload every ratio there is. Cover crops to the box, so a portrait photo
      // does not leave a stripe of grey on either side.
      object-fit: cover;
    }

    .thumb__none {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--s-1);
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
    }

    // Dark on any photo, so it reads whatever the car's colour or the background.
    .thumb__count {
      position: absolute;
      left: var(--s-2);
      bottom: var(--s-2);
      display: inline-flex;
      align-items: center;
      gap: var(--s-1);
      padding: var(--s-0) var(--s-2);
      border-radius: var(--r-sm);
      background: rgb(11 31 58 / 78%);
      color: #fff;
      font-size: var(--t-xs);
      font-weight: var(--fw-bold);
      line-height: 1.5;
      font-variant-numeric: tabular-nums;
    }

    .thumb__fav {
      position: absolute;
      top: var(--s-2);
      right: var(--s-2);

      // On a phone the button is larger than the disc it shows; it moves out by the
      // difference, so the disc sits as far from the corner as the photo count does.
      @include t.touch {
        top: calc(var(--s-2) - var(--fav-disc-inset, 0px));
        right: calc(var(--s-2) - var(--fav-disc-inset, 0px));
      }
    }
  `,
})
export class ListingThumb {
  readonly listing = input.required<PublicListingSummary>();
  /** What the result is called: the photo's alternative text and the heart's name. */
  readonly alt = input.required<string>();
  /** Load at once rather than lazily: for the results on screen when the page opens. */
  readonly eager = input(false);

  /** Set when the image failed to load, so the box falls back to the placeholder. */
  protected readonly photoBroken = signal(false);

  protected readonly photo = computed(() => (this.photoBroken() ? null : coverOf(this.listing())));

  protected readonly photoCount = computed(() => this.listing().photoCount ?? 0);

  protected readonly countLabel = computed(() =>
    this.photoCount() === 1 ? '1 photo' : `${this.photoCount()} photos`,
  );
}
