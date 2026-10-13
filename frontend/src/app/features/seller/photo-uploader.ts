import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';

import { ImageResponse, ImageStatus, ListingPhotosService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Alert } from '../../shared/ui/alert';
import { PHOTO_RULES, PhotoUploadService, rejectionFor } from './photo-upload.service';

/** A file on its way up, or one that did not make it. */
interface QueuedUpload {
  readonly key: number;
  readonly file: File;
  readonly name: string;
  readonly state: 'waiting' | 'sending' | 'processing' | 'failed';
  /** 0 to 100, while sending. */
  readonly percent: number;
  /** The French reason, when it failed. */
  readonly error: string | null;
}

let nextKey = 0;

/**
 * A listing's photos: add by dropping or choosing, reorder, pick the main one, remove.
 *
 * <p>The first photo is the main one — it is the picture on the results page — so
 * "définir comme principale" and reordering are the same operation, and there is no
 * separate "cover" flag to fall out of step with the order.
 *
 * <p>Reordering works three ways, because drag-and-drop alone would lock out anyone not
 * using a mouse: dragging a photo onto another, the two arrow buttons on each photo, and
 * the "principale" button that sends one straight to the front.
 *
 * <p>Uploads run one at a time. In parallel they would finish in an arbitrary order and
 * the photos would land in that order, which is not the one the seller chose them in.
 */
@Component({
  selector: 'app-photo-uploader',
  imports: [Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './photo-uploader.html',
  styleUrl: './photo-uploader.scss',
})
export class PhotoUploader {
  private readonly uploads = inject(PhotoUploadService);
  private readonly api = inject(ListingPhotosService);
  private readonly errors = inject(ErrorMessageService);

  readonly listingId = input.required<string>();

  /** The photos the listing already has. */
  readonly initial = input<readonly ImageResponse[]>([]);

  /** The listing's photos after every change, in order. */
  readonly changed = output<ImageResponse[]>();

  protected readonly photos = signal<ImageResponse[]>([]);
  protected readonly queue = signal<QueuedUpload[]>([]);
  /** A failed reorder or removal — shown above the photos, since it concerns them all. */
  protected readonly problem = signal<string | null>(null);
  /** Files refused before upload, each with its reason. */
  protected readonly refused = signal<string[]>([]);
  protected readonly dragOver = signal(false);
  /** The photo being dragged, by index. */
  private dragging: number | null = null;

  protected readonly rules = PHOTO_RULES;
  protected readonly accept = PHOTO_RULES.contentTypes.join(',');
  protected readonly full = computed(() => this.photos().length >= PHOTO_RULES.maxPerListing);
  protected readonly busy = computed(() =>
    this.queue().some((item) => item.state === 'sending' || item.state === 'processing'),
  );

  constructor() {
    effect(() => {
      this.photos.set(ready(this.initial()));
    });
  }

  // --- adding -------------------------------------------------------------

  protected onChosen(event: Event): void {
    const field = event.target as HTMLInputElement;
    this.add(Array.from(field.files ?? []));
    // Cleared so that choosing the same file again — after removing it — fires `change`.
    field.value = '';
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    // A photo dragged within the list to reorder it carries no files.
    this.add(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onDragOver(event: DragEvent): void {
    // Without this the browser's default wins and the drop navigates to the image.
    event.preventDefault();
    if (event.dataTransfer?.types.includes('Files')) {
      this.dragOver.set(true);
    }
  }

  private add(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    const refused: string[] = [];
    const accepted: QueuedUpload[] = [];
    // Counted as it goes, so the twenty-first file of one drop is the one refused.
    let count = this.photos().length + this.queue().filter((item) => item.state !== 'failed').length;

    for (const file of files) {
      const reason = rejectionFor(file, count);
      if (reason) {
        refused.push(reason);
      } else {
        accepted.push({ key: ++nextKey, file, name: file.name, state: 'waiting', percent: 0, error: null });
        count++;
      }
    }

    this.refused.set(refused);
    this.queue.update((queue) => [...queue, ...accepted]);
    this.pump();
  }

  /** Starts the next waiting upload, unless one is already running. */
  private pump(): void {
    if (this.busy()) {
      return;
    }
    const next = this.queue().find((item) => item.state === 'waiting');
    if (!next) {
      return;
    }
    this.patch(next.key, { state: 'sending', percent: 0 });

    this.uploads.upload(this.listingId(), next.file).subscribe({
      next: (event) => {
        if (event.phase === 'sending') {
          this.patch(next.key, { state: 'sending', percent: Math.round(event.progress * 100) });
        } else if (event.phase === 'processing') {
          this.patch(next.key, { state: 'processing', percent: 100 });
        } else {
          this.queue.update((queue) => queue.filter((item) => item.key !== next.key));
          this.setPhotos([...this.photos(), event.image]);
          this.pump();
        }
      },
      error: (error: unknown) => {
        // The reason, in French, next to the file it concerns — and the queue carries on:
        // one unreadable photo should not strand the nine behind it.
        this.patch(next.key, { state: 'failed', error: this.errors.messageFor(error) });
        this.pump();
      },
    });
  }

  protected retry(item: QueuedUpload): void {
    this.patch(item.key, { state: 'waiting', error: null, percent: 0 });
    this.pump();
  }

  protected dismiss(item: QueuedUpload): void {
    this.queue.update((queue) => queue.filter((entry) => entry.key !== item.key));
  }

  private patch(key: number, change: Partial<QueuedUpload>): void {
    this.queue.update((queue) => queue.map((item) => (item.key === key ? { ...item, ...change } : item)));
  }

  // --- reordering ---------------------------------------------------------

  protected makeMain(index: number): void {
    this.move(index, 0);
  }

  protected move(from: number, to: number): void {
    const before = this.photos();
    if (from === to || to < 0 || to >= before.length) {
      return;
    }
    const after = [...before];
    const [moved] = after.splice(from, 1);
    after.splice(to, 0, moved);

    // Shown at once and corrected if the API refuses: a photo that visibly lags a second
    // behind the drag feels broken even when it is not.
    this.problem.set(null);
    this.photos.set(after);

    this.api
      .reorder({
        listingId: this.listingId(),
        reorderImagesRequest: { imageIds: after.map((photo) => photo.id!).filter(Boolean) },
      })
      .subscribe({
        next: (saved) => this.setPhotos(ready(saved)),
        error: (error: unknown) => {
          this.photos.set(before);
          this.problem.set(this.errors.messageFor(error));
        },
      });
  }

  protected onPhotoDragStart(index: number, event: DragEvent): void {
    this.dragging = index;
    event.dataTransfer?.setData('text/plain', String(index));
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  protected onPhotoDrop(index: number, event: DragEvent): void {
    if (this.dragging === null) {
      // Files dropped on a photo: let the drop zone around it take them.
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    const from = this.dragging;
    this.dragging = null;
    this.move(from, index);
  }

  protected onPhotoDragEnd(): void {
    this.dragging = null;
  }

  // --- removing -----------------------------------------------------------

  protected remove(photo: ImageResponse): void {
    if (!photo.id) {
      return;
    }
    const before = this.photos();
    this.problem.set(null);
    this.photos.set(before.filter((entry) => entry.id !== photo.id));

    this.api.delete1({ listingId: this.listingId(), imageId: photo.id }).subscribe({
      next: () => this.changed.emit(this.photos()),
      error: (error: unknown) => {
        this.photos.set(before);
        this.problem.set(this.errors.messageFor(error));
      },
    });
  }

  protected thumbnail(photo: ImageResponse): string | null {
    const variants = photo.variants ?? {};
    return variants['THUMBNAIL']?.url ?? variants['CARD']?.url ?? null;
  }

  private setPhotos(photos: ImageResponse[]): void {
    this.photos.set(photos);
    this.changed.emit(photos);
  }
}

/** Ready photos only, in the listing's order. A pending or failed row is not a photo. */
function ready(images: readonly ImageResponse[]): ImageResponse[] {
  return images
    .filter((image) => image.status === undefined || image.status === ImageStatus.READY)
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
}
