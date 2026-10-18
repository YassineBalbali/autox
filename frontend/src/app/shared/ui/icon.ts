import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ICONS, IconName } from './icons.data';

/** One drawing instruction: an SVG element and its attributes, as Lucide stores them. */
export type IconNode = readonly (readonly [string, Readonly<Record<string, string>>])[];

/**
 * The attributes the template binds, per element. An icon that needs another one would
 * draw wrongly rather than fail, so `icon.spec.ts` checks every icon against this list.
 */
export const BOUND_ATTRIBUTES: Readonly<Record<string, readonly string[]>> = {
  path: ['d'],
  circle: ['cx', 'cy', 'r'],
  rect: ['x', 'y', 'width', 'height', 'rx', 'ry'],
  line: ['x1', 'y1', 'x2', 'y2'],
  polyline: ['points'],
  polygon: ['points'],
  ellipse: ['cx', 'cy', 'rx', 'ry'],
};

/**
 * An icon from the one set the site uses — Lucide, outlined, 24-unit grid.
 *
 * <p>Drawn from data rather than an SVG string: Angular's sanitiser removes SVG set
 * through `innerHTML`, and building elements from a known list of tags and attributes
 * means nothing in the icon data can ever reach the page as markup.
 *
 * <p>Decorative by default — hidden from assistive technology, because an icon beside a
 * word repeats the word. Given a `label`, it is announced as an image with that name: the
 * case of an icon that stands alone, such as a heart button.
 *
 * <p>Takes its colour from the text around it (`currentColor`) and its size from `size`,
 * in pixels.
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'icon' },
  template: `
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-linecap="round"
      stroke-linejoin="round"
      focusable="false"
      [attr.width]="size()"
      [attr.height]="size()"
      [attr.stroke-width]="strokeWidth()"
      [attr.aria-hidden]="label() ? null : 'true'"
      [attr.role]="label() ? 'img' : null"
      [attr.aria-label]="label()"
    >
      @for (node of nodes(); track $index) {
        @switch (node[0]) {
          @case ('path') {
            <path [attr.d]="node[1]['d']" />
          }
          @case ('circle') {
            <circle [attr.cx]="node[1]['cx']" [attr.cy]="node[1]['cy']" [attr.r]="node[1]['r']" />
          }
          @case ('rect') {
            <rect
              [attr.x]="node[1]['x']"
              [attr.y]="node[1]['y']"
              [attr.width]="node[1]['width']"
              [attr.height]="node[1]['height']"
              [attr.rx]="node[1]['rx']"
              [attr.ry]="node[1]['ry']"
            />
          }
          @case ('line') {
            <line
              [attr.x1]="node[1]['x1']"
              [attr.y1]="node[1]['y1']"
              [attr.x2]="node[1]['x2']"
              [attr.y2]="node[1]['y2']"
            />
          }
          @case ('polyline') {
            <polyline [attr.points]="node[1]['points']" />
          }
          @case ('polygon') {
            <polygon [attr.points]="node[1]['points']" />
          }
          @case ('ellipse') {
            <ellipse
              [attr.cx]="node[1]['cx']"
              [attr.cy]="node[1]['cy']"
              [attr.rx]="node[1]['rx']"
              [attr.ry]="node[1]['ry']"
            />
          }
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      line-height: 0;
    }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input(18);
  /** Lucide is drawn at 2; small sizes read better a little lighter. */
  readonly strokeWidth = input(1.75);
  /** Set only when the icon is the whole of what the user reads. */
  readonly label = input<string | null>(null);

  protected readonly nodes = computed<IconNode>(() => ICONS[this.name()]);
}
