import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, ImageResponse, ImageStatus } from '../../core/api';
import { PhotoUploadService, UploadEvent } from './photo-upload.service';
import { PhotoUploader } from './photo-uploader';

const BASE = 'http://api.test';

/** Stands in for the three-step upload: each call hands back a subject the test drives. */
class FakeUploads {
  readonly calls: { listingId: string; file: File; events: Subject<UploadEvent> }[] = [];
  upload(listingId: string, file: File) {
    const events = new Subject<UploadEvent>();
    this.calls.push({ listingId, file, events });
    return events.asObservable();
  }
}

/**
 * The photo manager.
 *
 * <p>What is tested is what a seller would notice going wrong: photos landing in a
 * different order from the one they were chosen in, one bad file stopping the rest, a
 * reorder that the screen shows and the server never received, a photo that vanishes from
 * the screen while the delete failed.
 */
describe('PhotoUploader', () => {
  let fixture: ComponentFixture<PhotoUploader>;
  let backend: HttpTestingController;
  let uploads: FakeUploads;
  let emitted: ImageResponse[][];

  const photo = (id: string, position: number, over: Partial<ImageResponse> = {}): ImageResponse => ({
    id,
    position,
    status: ImageStatus.READY,
    variants: { THUMBNAIL: { url: `http://img.test/${id}/thumbnail.jpg` } },
    ...over,
  });

  const file = (name: string, type = 'image/jpeg', size = 1024) => new File([new Uint8Array(size)], name, { type });

  async function render(initial: ImageResponse[] = []): Promise<void> {
    TestBed.resetTestingModule();
    uploads = new FakeUploads();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: BASE },
        { provide: PhotoUploadService, useValue: uploads },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PhotoUploader);
    fixture.componentRef.setInput('listingId', 'l1');
    fixture.componentRef.setInput('initial', initial);
    emitted = [];
    fixture.componentInstance.changed.subscribe((photos) => emitted.push(photos));
    await fixture.whenStable();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const settle = () => fixture.whenStable();
  const shownIds = () =>
    Array.from(el().querySelectorAll('.photo img')).map((img) => (img as HTMLImageElement).src.split('/').at(-2));
  const queue = () => Array.from(el().querySelectorAll('.upload')) as HTMLElement[];
  const button = (card: number, label: string) =>
    Array.from(el().querySelectorAll('.photo')[card].querySelectorAll('button')).find((candidate) =>
      (candidate.getAttribute('aria-label') ?? candidate.textContent ?? '').includes(label),
    ) as HTMLButtonElement;

  /** Drops files on the zone, as a drag from the desktop does. */
  async function drop(...files: File[]): Promise<void> {
    const event = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', { value: { files, types: ['Files'] } });
    el().querySelector('.dropzone')!.dispatchEvent(event);
    await settle();
  }

  const apiError = (code: ErrorCode, status = 409) =>
    new HttpErrorResponse({ status, error: { code }, url: `${BASE}/x` });

  describe('what the listing already has', () => {
    it('shows the photos in the listing’s order, the first marked as the main one', async () => {
      await render([photo('b', 1), photo('a', 0), photo('c', 2)]);

      expect(shownIds()).toEqual(['a', 'b', 'c']);
      const tags = el().querySelectorAll('.photo__main');
      expect(tags).toHaveLength(1);
      expect(el().querySelectorAll('.photo')[0].contains(tags[0])).toBe(true);
    });

    it('does not count a pending or failed upload as a photo', async () => {
      await render([photo('a', 0), photo('b', 1, { status: ImageStatus.PENDING }), photo('c', 2, { status: ImageStatus.FAILED })]);

      expect(shownIds()).toEqual(['a']);
    });

    it('says a photo is needed when there is none', async () => {
      await render();

      expect(el().textContent).toContain('Il en faut au moins une pour mettre l’annonce en ligne.');
    });
  });

  describe('adding', () => {
    it('uploads one file at a time, in the order they were chosen', async () => {
      // In parallel they would finish — and land — in whatever order the network chose.
      await render();
      await drop(file('un.jpg'), file('deux.jpg'), file('trois.jpg'));

      expect(uploads.calls.map((call) => call.file.name)).toEqual(['un.jpg']);
      expect(queue().map((row) => row.textContent)).toEqual([
        expect.stringContaining('un.jpg'),
        expect.stringContaining('En attente'),
        expect.stringContaining('En attente'),
      ]);

      uploads.calls[0].events.next({ phase: 'done', image: photo('a', 0) });
      await settle();
      expect(uploads.calls.map((call) => call.file.name)).toEqual(['un.jpg', 'deux.jpg']);

      uploads.calls[1].events.next({ phase: 'done', image: photo('b', 1) });
      uploads.calls[2].events.next({ phase: 'done', image: photo('c', 2) });
      await settle();

      expect(shownIds()).toEqual(['a', 'b', 'c']);
      expect(queue()).toHaveLength(0);
      expect(uploads.calls.every((call) => call.listingId === 'l1')).toBe(true);
    });

    it('shows how far the upload has got, then that the image is being checked', async () => {
      await render();
      await drop(file('un.jpg'));

      uploads.calls[0].events.next({ phase: 'sending', progress: 0.42 });
      await settle();
      const bar = el().querySelector('progress') as HTMLProgressElement;
      expect(bar.value).toBe(42);
      expect(bar.getAttribute('aria-label')).toBe('Envoi de un.jpg');
      expect(queue()[0].textContent).toContain('42 %');

      uploads.calls[0].events.next({ phase: 'processing' });
      await settle();
      expect(queue()[0].textContent).toContain('Vérification de l’image');
      expect(el().querySelector('progress')).toBeNull();
    });

    it('tells the page about each photo as it arrives', async () => {
      await render([photo('a', 0)]);
      await drop(file('deux.jpg'));
      uploads.calls[0].events.next({ phase: 'done', image: photo('b', 1) });
      await settle();

      expect(emitted.at(-1)?.map((entry) => entry.id)).toEqual(['a', 'b']);
    });

    it('refuses a wrong file before sending anything, by name, and sends the others', async () => {
      await render();
      await drop(file('notes.pdf', 'application/pdf'), file('bonne.jpg'), file('enorme.jpg', 'image/jpeg', 11 * 1024 * 1024));

      const refused = Array.from(el().querySelectorAll('.uploader__refused li')).map((item) => item.textContent);
      expect(refused).toEqual([
        '« notes.pdf » n’est pas une image JPEG ou PNG.',
        '« enorme.jpg » est trop volumineuse (11 Mo). La taille maximale est de 10 Mo.',
      ]);
      expect(uploads.calls.map((call) => call.file.name)).toEqual(['bonne.jpg']);
    });

    it('refuses the files beyond the twentieth, counting those already waiting', async () => {
      await render(Array.from({ length: 18 }, (_, index) => photo(`p${index}`, index)));
      await drop(file('19.jpg'), file('20.jpg'), file('21.jpg'));

      expect(queue()).toHaveLength(2);
      expect(el().querySelector('.uploader__refused')?.textContent).toContain('« 21.jpg »');
      expect(el().querySelector('.uploader__refused')?.textContent).toContain('au maximum 20 photos');
    });

    it('replaces the chooser with an explanation when the listing is full', async () => {
      await render(Array.from({ length: 20 }, (_, index) => photo(`p${index}`, index)));

      expect(el().querySelector('input[type=file]')).toBeNull();
      expect(el().querySelector('.dropzone')?.textContent).toContain('le maximum de 20 photos');
    });

    it('accepts only JPEG and PNG in the file chooser', async () => {
      await render();

      expect((el().querySelector('input[type=file]') as HTMLInputElement).accept).toBe('image/jpeg,image/png');
    });
  });

  describe('when an upload fails', () => {
    it('says why in French next to the file, and carries on with the next', async () => {
      // One unreadable photo must not strand the nine behind it.
      await render();
      await drop(file('petite.jpg'), file('bonne.jpg'));

      uploads.calls[0].events.error(apiError(ErrorCode.IMAGE_TOO_SMALL, 422));
      await settle();

      expect(queue()[0].querySelector('.upload__error')?.textContent).toBe(
        'Cette image est trop petite. Choisissez une photo plus grande.',
      );
      expect(queue()[0].textContent).not.toContain('IMAGE_TOO_SMALL');
      expect(uploads.calls.map((call) => call.file.name)).toEqual(['petite.jpg', 'bonne.jpg']);
    });

    it('does not count the failed file as a photo', async () => {
      await render();
      await drop(file('petite.jpg'));
      uploads.calls[0].events.error(apiError(ErrorCode.IMAGE_TOO_SMALL, 422));
      await settle();

      expect(shownIds()).toEqual([]);
      expect(emitted).toEqual([]);
    });

    it('tries the same file again on "Réessayer"', async () => {
      await render();
      await drop(file('un.jpg'));
      uploads.calls[0].events.error(new HttpErrorResponse({ status: 0 }));
      await settle();

      (queue()[0].querySelector('button') as HTMLButtonElement).click();
      await settle();

      expect(uploads.calls).toHaveLength(2);
      expect(uploads.calls[1].file.name).toBe('un.jpg');
      expect(queue()[0].querySelector('.upload__error')).toBeNull();
    });

    it('drops the file from the list on "Retirer"', async () => {
      await render();
      await drop(file('un.jpg'));
      uploads.calls[0].events.error(new HttpErrorResponse({ status: 0 }));
      await settle();

      (queue()[0].querySelectorAll('button')[1] as HTMLButtonElement).click();
      await settle();

      expect(queue()).toHaveLength(0);
      expect(uploads.calls).toHaveLength(1);
    });
  });

  describe('reordering', () => {
    const ORDER = `${BASE}/api/v1/listings/l1/images/order`;

    it('makes a photo the main one by sending it to the front', async () => {
      await render([photo('a', 0), photo('b', 1), photo('c', 2)]);

      button(2, 'comme photo principale').click();
      await settle();

      // Shown at once…
      expect(shownIds()).toEqual(['c', 'a', 'b']);
      // …and sent: the whole order, since position 0 is what "main" means.
      const request = backend.expectOne(ORDER);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({ imageIds: ['c', 'a', 'b'] });
      request.flush([photo('c', 0), photo('a', 1), photo('b', 2)]);
      await settle();

      expect(emitted.at(-1)?.map((entry) => entry.id)).toEqual(['c', 'a', 'b']);
      expect(el().querySelectorAll('.photo')[0].querySelector('.photo__main')).not.toBeNull();
    });

    it('moves a photo one place with the arrows', async () => {
      await render([photo('a', 0), photo('b', 1), photo('c', 2)]);

      button(0, 'vers la droite').click();
      await settle();

      expect(backend.expectOne(ORDER).request.body).toEqual({ imageIds: ['b', 'a', 'c'] });
    });

    it('has no arrow pointing off either end, and no "principale" on the main photo', async () => {
      await render([photo('a', 0), photo('b', 1)]);

      expect(button(0, 'vers la gauche').disabled).toBe(true);
      expect(button(1, 'vers la droite').disabled).toBe(true);
      expect(button(0, 'comme photo principale')).toBeUndefined();
    });

    it('reorders when one photo is dragged onto another', async () => {
      await render([photo('a', 0), photo('b', 1), photo('c', 2)]);
      const cards = el().querySelectorAll('.photo');
      const transfer = { setData: () => undefined, effectAllowed: '', files: [], types: [] };
      const drag = (type: string, target: Element) => {
        const event = new Event(type, { bubbles: true, cancelable: true });
        Object.defineProperty(event, 'dataTransfer', { value: transfer });
        target.dispatchEvent(event);
      };

      drag('dragstart', cards[2]);
      drag('drop', cards[0]);
      await settle();

      expect(backend.expectOne(ORDER).request.body).toEqual({ imageIds: ['c', 'a', 'b'] });
      // A reorder is not an upload.
      expect(uploads.calls).toHaveLength(0);
    });

    it('puts the photos back and says so when the API refuses the new order', async () => {
      await render([photo('a', 0), photo('b', 1)]);

      button(1, 'comme photo principale').click();
      await settle();
      backend.expectOne(ORDER).flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'Error' });
      await settle();

      expect(shownIds()).toEqual(['a', 'b']);
      expect(el().querySelector('app-alert')?.textContent).toBeTruthy();
      expect(emitted).toEqual([]);
    });
  });

  describe('removing', () => {
    const IMAGE = `${BASE}/api/v1/listings/l1/images/b`;

    it('deletes the photo and tells the page what is left', async () => {
      await render([photo('a', 0), photo('b', 1)]);

      button(1, 'Supprimer la photo 2').click();
      await settle();
      const request = backend.expectOne(IMAGE);
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
      await settle();

      expect(shownIds()).toEqual(['a']);
      expect(emitted.at(-1)?.map((entry) => entry.id)).toEqual(['a']);
    });

    it('brings the photo back when the delete fails, and does not tell the page it went', async () => {
      // Otherwise the preview would say "no photo" about a listing that still has one.
      await render([photo('a', 0), photo('b', 1)]);

      button(1, 'Supprimer la photo 2').click();
      await settle();
      backend.expectOne(IMAGE).flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'Error' });
      await settle();

      expect(shownIds()).toEqual(['a', 'b']);
      expect(emitted).toEqual([]);
      expect(el().querySelector('app-alert')).not.toBeNull();
    });
  });
});
