import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The footer.
 *
 * <p>Deliberately thin, and carrying no links. The footer of a marketplace is where the
 * legal pages live — conditions générales, mentions légales, politique de confidentialité
 * — and none of them exist. A link to a page that is not there is worse than no link: the
 * wildcard route would send the reader to their own account, and on a French marketplace
 * these are obligations rather than decoration, so they should appear when they are real.
 *
 * <p>What it does do is close the page. Without it a short screen on a tall desktop window
 * ends in an unbounded expanse of background, which is the thing that makes an application
 * look unfinished even when every screen above it is right.
 */
@Component({
  selector: 'app-site-footer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page footer__inner">
      <p class="footer__brand">AutoX</p>
      <p class="footer__note" i18n="@@footerNote">
        Annonces de véhicules d’occasion en France.
      </p>
      <p class="footer__legal">© {{ year }} AutoX</p>
    </div>
  `,
  styles: `
    @use '../../styles/tokens' as t;

    :host {
      display: block;
      margin-top: var(--s-7);
      background: var(--c-surface);
      border-top: 1px solid var(--c-line);
    }

    .footer__inner {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      padding-block: var(--s-5);

      // One line on a desktop width, with the copyright pushed to the far edge so it lines
      // up with the account menu directly above it.
      @media (min-width: t.$bp-md) {
        flex-direction: row;
        align-items: baseline;
        gap: var(--s-4);
      }
    }

    .footer__brand {
      font-weight: var(--fw-bold);
      color: var(--c-ink);
    }

    .footer__note {
      flex: 1;
      // Overrides the global reading measure on p. Capped at 36rem the note cannot expand,
      // so flex:1 does nothing and the copyright ends up stranded in the middle of the
      // footer instead of against the right edge of the container.
      max-width: none;
      font-size: var(--t-sm);
      color: var(--c-ink-soft);
    }

    .footer__legal {
      font-size: var(--t-sm);
      color: var(--c-ink-faint);
    }
  `,
})
export class SiteFooter {
  /** Read once at construction. A page open across midnight on 31 December can cope. */
  protected readonly year = new Date().getFullYear();
}
