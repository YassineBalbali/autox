import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { GalleryImage, PhotoGallery } from './photo-gallery';

const image = (id: string): GalleryImage => ({
  id,
  thumbnail: `http://img.test/${id}-thumb.jpg`,
  medium: `http://img.test/${id}-card.jpg`,
  large: `http://img.test/${id}-full.jpg`,
  width: 1600,
  height: 1200,
});

@Component({
  imports: [PhotoGallery],
  template: `<app-photo-gallery [images]="images()" subject="Peugeot 308" />`,
})
class Host {
  readonly images = signal<GalleryImage[]>([image('a'), image('b'), image('c')]);
}

/**
 * The photo gallery.
 *
 * <p>Most of what is tested is the keyboard and the enlarged view, because that is where a
 * gallery usually fails the people who cannot use a mouse: arrows that do nothing, a
 * dialog that opens with focus still behind it, and no way out but reloading the page.
 */
describe('PhotoGallery', () => {
  let fixture: ComponentFixture<Host>;
  let root: HTMLElement;

  async function render(images?: GalleryImage[]): Promise<void> {
    fixture = TestBed.createComponent(Host);
    if (images) {
      fixture.componentInstance.images.set(images);
    }
    // Attached to the document, so focus() and document.activeElement behave as they do
    // in a browser.
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
    root = fixture.nativeElement;
  }

  const gallery = () => root.querySelector('app-photo-gallery') as HTMLElement;
  const main = () => root.querySelector('.gallery__main') as HTMLButtonElement;
  const shown = () => (root.querySelector('.gallery__img') as HTMLImageElement).getAttribute('src');
  const count = () => root.querySelector('.gallery__count')?.textContent?.trim();
  const dialog = () => root.querySelector('.lightbox') as HTMLElement | null;

  async function press(key: string, target: HTMLElement = gallery(), shiftKey = false): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, shiftKey });
    target.dispatchEvent(event);
    await fixture.whenStable();
    return event;
  }

  async function click(element: Element | null): Promise<void> {
    (element as HTMLElement).click();
    await fixture.whenStable();
  }

  describe('the main image and the thumbnails', () => {
    it('starts on the first photo, with a thumbnail for each', async () => {
      await render();

      expect(shown()).toBe('http://img.test/a-card.jpg');
      expect(count()).toBe('1 / 3');
      expect(root.querySelectorAll('.gallery__thumb')).toHaveLength(3);
    });

    it('offers the large file to dense screens, not only the medium one', async () => {
      await render();
      const srcset = (root.querySelector('.gallery__img') as HTMLImageElement).getAttribute('srcset');

      expect(srcset).toContain('a-card.jpg 800w');
      expect(srcset).toContain('a-full.jpg 1600w');
    });

    it('describes the photo, and which one of how many it is', async () => {
      await render();
      expect((root.querySelector('.gallery__img') as HTMLImageElement).alt).toBe('Photo 1 sur 3 — Peugeot 308');
    });

    it('shows the photo whose thumbnail is clicked, and marks it', async () => {
      await render();
      const thumbs = root.querySelectorAll('.gallery__thumb');

      await click(thumbs[2]);

      expect(shown()).toBe('http://img.test/c-card.jpg');
      expect(thumbs[2].getAttribute('aria-current')).toBe('true');
      expect(thumbs[0].getAttribute('aria-current')).toBeNull();
    });

    it('steps with the previous and next buttons', async () => {
      await render();

      await click(root.querySelector('.gallery__arrow--next'));
      expect(count()).toBe('2 / 3');
      await click(root.querySelector('.gallery__arrow--prev'));
      expect(count()).toBe('1 / 3');
    });
  });

  describe('the arrow keys', () => {
    it('move to the next and the previous photo', async () => {
      await render();

      await press('ArrowRight');
      expect(shown()).toBe('http://img.test/b-card.jpg');
      await press('ArrowLeft');
      expect(shown()).toBe('http://img.test/a-card.jpg');
    });

    it('wrap round at both ends', async () => {
      await render();

      await press('ArrowLeft');
      expect(count()).toBe('3 / 3');
      await press('ArrowRight');
      expect(count()).toBe('1 / 3');
    });

    it('do not scroll the page while they are moving the gallery', async () => {
      await render();
      expect((await press('ArrowRight')).defaultPrevented).toBe(true);
    });

    it('are ignored when focus is somewhere else on the page', async () => {
      // A listener on the whole document would take the arrows away from someone reading
      // the description.
      await render();
      const elsewhere = document.createElement('button');
      document.body.appendChild(elsewhere);

      await press('ArrowRight', elsewhere);

      expect(count()).toBe('1 / 3');
      elsewhere.remove();
    });
  });

  describe('the enlarged view', () => {
    it('opens when the main image is activated, as a modal dialog', async () => {
      await render();
      expect(main().getAttribute('aria-label')).toBe('Agrandir la photo');

      await click(main());

      expect(dialog()?.getAttribute('role')).toBe('dialog');
      expect(dialog()?.getAttribute('aria-modal')).toBe('true');
      expect((root.querySelector('.lightbox__img') as HTMLImageElement).getAttribute('src')).toBe(
        'http://img.test/a-full.jpg',
      );
    });

    it('moves focus into the dialog when it opens', async () => {
      // Otherwise a keyboard user has opened a picture and is still operating the page
      // behind it.
      await render();
      await click(main());

      expect(document.activeElement?.classList).toContain('lightbox__close');
    });

    it('closes on Escape and gives focus back to the image that opened it', async () => {
      await render();
      await click(main());

      await press('Escape', document.activeElement as HTMLElement);

      expect(dialog()).toBeNull();
      expect(document.activeElement).toBe(main());
    });

    it('closes on its button, and on the backdrop', async () => {
      await render();
      await click(main());
      await click(root.querySelector('.lightbox__close'));
      expect(dialog()).toBeNull();

      await click(main());
      await click(dialog());
      expect(dialog()).toBeNull();
    });

    it('stays open when the picture itself is clicked', async () => {
      await render();
      await click(main());
      await click(root.querySelector('.lightbox__img'));

      expect(dialog()).not.toBeNull();
    });

    it('follows the arrow keys, and stays in step with the page behind it', async () => {
      await render();
      await click(main());

      await press('ArrowRight', document.activeElement as HTMLElement);

      expect(root.querySelector('.lightbox__count')?.textContent?.trim()).toBe('2 / 3');
      await press('Escape', document.activeElement as HTMLElement);
      // The page shows the photo the visitor was last looking at, not the one they left.
      expect(count()).toBe('2 / 3');
    });

    it('keeps Tab inside it', async () => {
      await render();
      await click(main());
      const stops = Array.from(dialog()!.querySelectorAll('button'));
      const first = stops[0];
      const last = stops[stops.length - 1];

      last.focus();
      const forward = await press('Tab', last);
      expect(document.activeElement).toBe(first);
      expect(forward.defaultPrevented).toBe(true);

      await press('Tab', first, true);
      expect(document.activeElement).toBe(last);
    });
  });

  describe('with fewer photos', () => {
    it('shows one photo with no thumbnails, arrows or counter', async () => {
      await render([image('only')]);

      expect(shown()).toBe('http://img.test/only-card.jpg');
      expect(root.querySelector('.gallery__thumbs')).toBeNull();
      expect(root.querySelector('.gallery__arrow')).toBeNull();
      expect(count()).toBeUndefined();
    });

    it('can still enlarge that one photo', async () => {
      await render([image('only')]);
      await click(main());

      expect(dialog()).not.toBeNull();
      expect(root.querySelector('.lightbox__arrow')).toBeNull();
    });

    it('shows a placeholder, in the same box, when there are none', async () => {
      await render([]);

      expect(root.querySelector('.gallery__empty')?.textContent).toContain('Pas de photo');
      expect(root.querySelector('img')).toBeNull();
    });
  });

  describe('when a photo fails to load', () => {
    it('drops that photo and shows the next, rather than a broken image', async () => {
      await render();

      (root.querySelector('.gallery__img') as HTMLImageElement).dispatchEvent(new Event('error'));
      await fixture.whenStable();

      expect(shown()).toBe('http://img.test/b-card.jpg');
      expect(root.querySelectorAll('.gallery__thumb')).toHaveLength(2);
      expect(count()).toBe('1 / 2');
    });

    it('falls back to the placeholder when none of them load', async () => {
      await render([image('only')]);

      (root.querySelector('.gallery__img') as HTMLImageElement).dispatchEvent(new Event('error'));
      await fixture.whenStable();

      expect(root.querySelector('.gallery__empty')).not.toBeNull();
    });
  });

  it('goes back to the first photo when it is given a different listing’s photos', async () => {
    await render();
    await press('ArrowRight');
    expect(count()).toBe('2 / 3');

    fixture.componentInstance.images.set([image('x'), image('y')]);
    await fixture.whenStable();

    expect(shown()).toBe('http://img.test/x-card.jpg');
    expect(count()).toBe('1 / 2');
  });
});
