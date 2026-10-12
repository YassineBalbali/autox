import { ChangeDetectionStrategy, Component } from '@angular/core';

import { Logo } from '../shared/brand/logo';

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
  imports: [Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page footer__inner">
      <app-logo class="footer__logo" />
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
      margin-top: var(--s-6);
      background: var(--c-surface);
      border-top: var(--border);
    }

    .footer__inner {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      padding-block: var(--s-3);

      // One line on a desktop width, with the copyright against the right edge of the
      // container, under the account menu.
      @media (min-width: t.$bp-md) {
        flex-direction: row;
        align-items: center;
        gap: var(--s-4);
      }
    }

    // Smaller than in the header: here the mark signs the page off, it does not lead it.
    .footer__logo {
      --logo-h: 14px;
    }

    .footer__note {
      flex: 1;
      // Overrides the global reading measure on p, which would stop it expanding and
      // strand the copyright mid-footer.
      max-width: none;
      font-size: var(--t-xs);
      color: var(--c-ink-soft);
    }

    .footer__legal {
      font-size: var(--t-xs);
      color: var(--c-ink-faint);
    }
  `,
})
export class SiteFooter {
  /** Read once at construction. A page open across midnight on 31 December can cope. */
  protected readonly year = new Date().getFullYear();
}
