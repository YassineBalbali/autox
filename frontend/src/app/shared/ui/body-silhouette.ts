import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { SILHOUETTES, SilhouetteName } from './body-silhouettes.data';

/**
 * A kind of car, drawn in profile: the home page's body-type tiles.
 *
 * <p>The same construction as {@link Icon}: drawn from data, never from an SVG string,
 * outlined in the colour of the text around it, decorative — the tile's label says what it
 * is, so the drawing is hidden from assistive technology.
 *
 * <p>Sized by `width`; the height follows from the 2:1 grid the drawings are made on.
 */
@Component({
  selector: 'app-body-silhouette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'silhouette' },
  template: `
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 64 32"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      focusable="false"
      aria-hidden="true"
      [attr.width]="width()"
      [attr.height]="width() / 2"
    >
      @for (d of drawing().paths; track $index) {
        <path [attr.d]="d" />
      }
      @for (wheel of drawing().wheels; track $index) {
        <circle [attr.cx]="wheel[0]" [attr.cy]="wheel[1]" [attr.r]="wheel[2]" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      line-height: 0;
    }
  `,
})
export class BodySilhouette {
  readonly name = input.required<SilhouetteName>();
  /** In pixels. */
  readonly width = input(64);

  protected readonly drawing = computed(() => SILHOUETTES[this.name()]);
}
