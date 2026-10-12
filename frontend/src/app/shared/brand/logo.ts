import { ChangeDetectionStrategy, Component } from '@angular/core';

import { WORDMARK } from './wordmark.paths';

/**
 * The AutoX wordmark, inline.
 *
 * <p>Inline SVG rather than an `<img>` pointing at the asset, for two reasons. The fills
 * are `var(--c-navy)` and `var(--c-accent)`, so the mark is drawn in the interface's own
 * tokens and cannot be a different blue from the buttons beside it. And there is no
 * request: the logo paints with the first frame instead of popping in after it.
 *
 * <p>`public/autox-wordmark.svg` is the same artwork as a standalone file, for everything
 * that is not this application — an email, a social card, a partner's page. The two are
 * kept identical by `wordmark.spec.ts`.
 *
 * <p>Sized by `--logo-h` on the host, which is what lets the header reserve its box.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // The artwork's own ratio, bound on the host so the box has its final width before the
  // SVG paints — which is what "reserving space" for the logo means in practice.
  host: { '[style.aspect-ratio]': 'ratio' },
  template: `
    <svg
      class="logo"
      [attr.viewBox]="viewBox"
      role="img"
      aria-label="AutoX"
      focusable="false"
    >
      <g [attr.transform]="'translate(' + mark.translate + ')'">
        <path class="logo__auto" [attr.d]="mark.auto" />
        <path class="logo__x" [attr.d]="mark.x" />
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: inline-block;
      height: var(--logo-h);
      line-height: 0;
    }

    .logo {
      display: block;
      width: 100%;
      height: 100%;
    }

    .logo__auto {
      fill: var(--c-navy);
    }

    .logo__x {
      fill: var(--c-accent);
    }
  `,
})
export class Logo {
  protected readonly mark = WORDMARK;
  protected readonly viewBox = `0 0 ${WORDMARK.width} ${WORDMARK.height}`;
  protected readonly ratio = `${WORDMARK.width} / ${WORDMARK.height}`;
}
