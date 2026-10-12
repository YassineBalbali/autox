import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';

/** One photo, in the sizes the gallery uses. */
export interface GalleryImage {
  readonly id: string;
  /** About 320px on the long edge — the strip of thumbnails. */
  readonly thumbnail: string;
  /** About 800px — the main image on a standard-density screen. */
  readonly medium: string;
  /** About 1600px — the main image on a dense screen, and the enlarged view. */
  readonly large: string;
  readonly width: number | null;
  readonly height: number | null;
}

/**
 * A listing's photographs: one large, the rest as thumbnails, and an enlarged view.
 *
 * <p>Three ways to move through them, because people reach for different ones: click a
 * thumbnail, press the previous/next buttons, or use the arrow keys. The arrow keys work
 * whenever focus is anywhere inside the gallery and always inside the enlarged view, but
 * not from the rest of the page — a listener on the whole document would hijack the
 * arrows from a reader scrolling the description.
 *
 * <p>The enlarged view is a modal dialog in the full sense: focus moves into it and is
 * held there, Escape closes it, and focus returns to the image that opened it. Without
 * those three a keyboard user opens the picture and is lost behind it.
 */
@Component({
  selector: 'app-photo-gallery',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './photo-gallery.html',
  styleUrl: './photo-gallery.scss',
})
export class PhotoGallery {
  readonly images = input.required<readonly GalleryImage[]>();

  /** What the photographs are of — the listing's title — for their alt text. */
  readonly subject = input('');

  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private readonly opener = viewChild<ElementRef<HTMLButtonElement>>('opener');

  private readonly position = signal(0);
  protected readonly enlarged = signal(false);

  /** Photos that failed to load, by id, so one dead object does not blank the gallery. */
  private readonly broken = signal<ReadonlySet<string>>(new Set());

  protected readonly usable = computed(() =>
    this.images().filter((image) => !this.broken().has(image.id)),
  );

  /** Clamped, so a list that shrinks — a new listing, a broken image — cannot strand it. */
  protected readonly index = computed(() =>
    Math.min(this.position(), Math.max(0, this.usable().length - 1)),
  );

  protected readonly current = computed(() => this.usable()[this.index()] ?? null);
  protected readonly many = computed(() => this.usable().length > 1);

  /** "Photo 2 sur 5 — Peugeot 308" */
  protected readonly alt = computed(() => {
    const place = `Photo ${this.index() + 1} sur ${this.usable().length}`;
    return this.subject() ? `${place} — ${this.subject()}` : place;
  });

  constructor() {
    // A different listing's photos start from the first one.
    effect(() => {
      this.images();
      this.position.set(0);
    });

    // Focus follows the dialog: in when it opens, back to the opener when it closes.
    effect(() => {
      if (this.enlarged()) {
        this.closeButton()?.nativeElement.focus();
      }
    });
  }

  protected show(index: number): void {
    this.position.set(index);
  }

  /** Wraps round: after the last photo comes the first. */
  protected step(delta: number): void {
    const count = this.usable().length;
    if (count > 1) {
      this.position.set((this.index() + delta + count) % count);
    }
  }

  protected open(): void {
    this.enlarged.set(true);
  }

  protected close(): void {
    if (this.enlarged()) {
      this.enlarged.set(false);
      this.opener()?.nativeElement.focus();
    }
  }

  /**
   * Closes when the backdrop itself was clicked — not the picture, and not a control.
   *
   * <p>Compared by identity rather than by stopping propagation on everything inside:
   * that way nothing inside the dialog needs a click handler it has no other use for.
   */
  protected closeOnBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.close();
    }
  }

  protected markBroken(image: GalleryImage): void {
    this.broken.update((broken) => new Set(broken).add(image.id));
  }

  @HostListener('keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft') {
      this.step(-1);
      event.preventDefault();
    } else if (event.key === 'ArrowRight') {
      this.step(1);
      event.preventDefault();
    } else if (event.key === 'Escape' && this.enlarged()) {
      this.close();
      event.preventDefault();
    } else if (event.key === 'Tab' && this.enlarged()) {
      this.keepFocusInside(event);
    }
  }

  /**
   * Holds Tab inside the enlarged view.
   *
   * <p>A dialog that lets focus tab out leaves a keyboard user operating the page behind
   * an overlay they cannot see through.
   */
  private keepFocusInside(event: KeyboardEvent): void {
    const dialog = this.host.nativeElement.querySelector('.lightbox');
    const stops = Array.from(dialog?.querySelectorAll<HTMLElement>('button') ?? []);
    if (stops.length === 0) {
      return;
    }
    const first = stops[0];
    const last = stops[stops.length - 1];
    const active = document.activeElement;

    if (event.shiftKey && active === first) {
      last.focus();
      event.preventDefault();
    } else if (!event.shiftKey && active === last) {
      first.focus();
      event.preventDefault();
    }
  }
}
