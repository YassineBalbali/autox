import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PublicListingSummary } from '../../core/api';
import { Favourites } from './favourites';
import { ListingThumb } from './listing-thumb';

const LISTING: PublicListingSummary = {
  id: 'l-1',
  title: 'Peugeot 308 BlueHDi 130 Allure Pack',
  photoCount: 7,
  coverImage: {
    CARD: { url: 'http://img.test/card.jpg', width: 800, height: 600 },
    THUMBNAIL: { url: 'http://img.test/thumb.jpg', width: 320, height: 240 },
  },
};

@Component({
  imports: [ListingThumb],
  // Inside a link, as on the results page, where the whole result is the link's target.
  template: `
    <a href="/annonces/l-1" (click)="opened = opened + 1">
      <app-listing-thumb [listing]="listing()" alt="Peugeot 308 BlueHDi 130" [eager]="eager()" />
    </a>
  `,
})
class Host {
  readonly listing = signal<PublicListingSummary>(LISTING);
  readonly eager = signal(false);
  opened = 0;
}

/** A result's picture, with the photo count and the heart on it. */
describe('ListingThumb', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  async function render(listing: PublicListingSummary = LISTING): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  describe('the photo', () => {
    it('uses the card variant, with its dimensions so the result does not jump', async () => {
      const image = (await render()).querySelector('img') as HTMLImageElement;

      expect(image.getAttribute('src')).toBe('http://img.test/card.jpg');
      expect(image.getAttribute('width')).toBe('800');
      expect(image.getAttribute('height')).toBe('600');
      expect(image.getAttribute('alt')).toBe('Peugeot 308 BlueHDi 130');
    });

    it('is lazy by default and eager for the results on screen at once', async () => {
      const root = await render();
      expect(root.querySelector('img')?.getAttribute('loading')).toBe('lazy');

      fixture.componentInstance.eager.set(true);
      await fixture.whenStable();
      expect(root.querySelector('img')?.getAttribute('loading')).toBe('eager');
    });

    it('shows a placeholder, in the same box, when the listing has none', async () => {
      const root = await render({ ...LISTING, coverImage: {} });

      expect(root.querySelector('img')).toBeNull();
      expect(root.querySelector('.thumb')?.textContent).toContain('Pas de photo');
    });

    it('shows the placeholder when the image fails to load', async () => {
      // A deleted object, an expired signed URL. A broken-image icon in a results list
      // looks like the whole site is broken.
      const root = await render();
      (root.querySelector('img') as HTMLImageElement).dispatchEvent(new Event('error'));
      await fixture.whenStable();

      expect(root.querySelector('img')).toBeNull();
      expect(root.textContent).toContain('Pas de photo');
    });
  });

  describe('the photo count', () => {
    it('shows how many photos there are, and says it in words', async () => {
      const count = (await render()).querySelector('.thumb__count') as HTMLElement;

      expect(count.querySelector('.thumb__figure')?.textContent?.trim()).toBe('7');
      expect(count.querySelector('.visually-hidden')?.textContent).toBe('7 photos');
    });

    it('is singular for one', async () => {
      const root = await render({ ...LISTING, photoCount: 1 });

      expect(root.querySelector('.thumb__count .visually-hidden')?.textContent).toBe('1 photo');
    });

    it('is not shown when there are none, or the figure is missing', async () => {
      expect((await render({ ...LISTING, photoCount: 0 })).querySelector('.thumb__count')).toBeNull();
      expect((await render({ ...LISTING, photoCount: undefined })).querySelector('.thumb__count')).toBeNull();
    });
  });

  describe('the heart', () => {
    function heart(root: HTMLElement): HTMLButtonElement {
      return root.querySelector('app-favourite-button button') as HTMLButtonElement;
    }

    it('is a toggle named after the listing', async () => {
      const button = heart(await render());

      expect(button.getAttribute('aria-pressed')).toBe('false');
      expect(button.getAttribute('aria-label')).toBe('Ajouter Peugeot 308 BlueHDi 130 aux favoris');
    });

    it('marks the listing, and unmarks it', async () => {
      const root = await render();

      heart(root).click();
      await fixture.whenStable();
      expect(heart(root).getAttribute('aria-pressed')).toBe('true');
      expect(heart(root).getAttribute('aria-label')).toBe('Retirer Peugeot 308 BlueHDi 130 des favoris');
      expect(TestBed.inject(Favourites).has('l-1')).toBe(true);

      heart(root).click();
      await fixture.whenStable();
      expect(heart(root).getAttribute('aria-pressed')).toBe('false');
      expect(TestBed.inject(Favourites).has('l-1')).toBe(false);
    });

    it('does not open the listing it sits on', async () => {
      const root = await render();
      const click = new MouseEvent('click', { bubbles: true, cancelable: true });

      heart(root).dispatchEvent(click);

      expect(click.defaultPrevented).toBe(true);
      expect(fixture.componentInstance.opened).toBe(0);
    });

    it('shows a listing already marked, on a later visit', async () => {
      localStorage.setItem('autox.favourites', JSON.stringify(['l-1']));

      expect(heart(await render()).getAttribute('aria-pressed')).toBe('true');
    });

    it('is not offered for a listing with no id to remember', async () => {
      expect(heart(await render({ ...LISTING, id: undefined }))).toBeNull();
    });
  });
});
