import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, Routes } from '@angular/router';

import { routes } from '../app.routes';
import { Logo } from '../shared/brand/logo';

/** One entry in a footer column. */
export interface FooterItem {
  readonly label: string;
  /** Top-level route path, without a leading slash, of the page this will link to. */
  readonly path: string;
}

export interface FooterColumn {
  readonly title: string;
  readonly items: readonly FooterItem[];
}

/**
 * What a marketplace footer carries: who we are, how to get help, the legal pages, and
 * how to reach us.
 *
 * <p>Declared in full now, although none of these pages exists, because the footer's
 * shape depends on them and because a French marketplace is obliged to publish several —
 * mentions légales, conditions générales, politique de confidentialité. Listing them here
 * is also the to-do list.
 */
export const FOOTER_COLUMNS: readonly FooterColumn[] = [
  {
    title: 'À propos',
    items: [
      { label: 'Qui sommes-nous', path: 'a-propos' },
      { label: 'Comment ça marche', path: 'comment-ca-marche' },
      { label: 'Espace professionnel', path: 'pro' },
    ],
  },
  {
    title: 'Aide',
    items: [
      { label: 'Centre d’aide', path: 'aide' },
      { label: 'Conseils pour acheter', path: 'conseils-achat' },
      { label: 'Conseils pour vendre', path: 'conseils-vente' },
      { label: 'Signaler une annonce', path: 'signalement' },
    ],
  },
  {
    title: 'Informations légales',
    items: [
      { label: 'Mentions légales', path: 'mentions-legales' },
      { label: 'Conditions générales', path: 'conditions-generales' },
      { label: 'Politique de confidentialité', path: 'confidentialite' },
      { label: 'Cookies', path: 'cookies' },
    ],
  },
  {
    title: 'Contact',
    items: [
      { label: 'Nous contacter', path: 'contact' },
      { label: 'Presse', path: 'presse' },
    ],
  },
];

/** An entry as rendered: a link when its page exists, plain text when it does not. */
export interface RenderedFooterItem extends FooterItem {
  readonly available: boolean;
}

/**
 * Marks each entry by whether the route table has a page for it.
 *
 * <p>The same rule as the header's navigation, for the same reason: the wildcard route
 * sends an unknown path to the home page, so a link to a page that does not exist would
 * not fail — it would quietly take the reader somewhere else. In a footer that is worse
 * than usual, because these are the legal pages: a "Mentions légales" link that opens the
 * search results is a link that claims an obligation is met when it is not.
 *
 * <p>So an entry without a page is shown as text, visibly not a link, and becomes one on
 * its own the day the route is added.
 */
export function resolveFooter(
  columns: readonly FooterColumn[],
  table: Routes,
): { title: string; items: RenderedFooterItem[] }[] {
  const pages = new Set(
    table
      .filter((route) => route.path !== '**' && !route.redirectTo)
      .filter((route) => route.loadComponent || route.component || route.children)
      .map((route) => route.path),
  );
  return columns.map((column) => ({
    title: column.title,
    items: column.items.map((item) => ({ ...item, available: pages.has(item.path) })),
  }));
}

/**
 * The site footer: four columns of where-to-go-next, and a closing line.
 *
 * <p>Inside `.page`, the same container as the header and the content, so its columns
 * start on the same vertical line as the wordmark above.
 */
@Component({
  selector: 'app-site-footer',
  imports: [Logo, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <nav class="footer__columns" aria-label="Pied de page" i18n-aria-label="@@footerLabel">
        @for (column of columns; track column.title) {
          <section class="footer__column">
            <h2 class="footer__title">{{ column.title }}</h2>
            <ul class="footer__list">
              @for (item of column.items; track item.path) {
                <li>
                  @if (item.available) {
                    <a class="footer__link" [routerLink]="'/' + item.path">{{ item.label }}</a>
                  } @else {
                    <!-- Not a link, and announced as unavailable rather than merely
                         looking grey: aria-disabled is what tells a screen reader. -->
                    <span class="footer__link footer__link--soon" aria-disabled="true">
                      {{ item.label }}
                    </span>
                  }
                </li>
              }
            </ul>
          </section>
        }
      </nav>

      <div class="footer__base">
        <app-logo class="footer__logo" />
        <p class="footer__note" i18n="@@footerNote">Annonces de véhicules d’occasion en France.</p>
        <p class="footer__legal">© {{ year }} AutoX</p>
      </div>
    </div>
  `,
  styles: `
    @use '../../styles/tokens' as t;

    :host {
      display: block;
      margin-top: var(--s-6);
      background: var(--c-surface);
      border-top: 2px solid var(--c-navy);
    }

    .footer__columns {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--s-4);
      padding-block: var(--s-5) var(--s-4);

      @media (min-width: t.$bp-md) {
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: var(--s-5);
      }
    }

    .footer__title {
      margin-bottom: var(--s-2);
      font-size: var(--t-xs);
      font-weight: var(--fw-bold);
      color: var(--c-ink);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .footer__list {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .footer__link {
      font-size: var(--t-sm);
      color: var(--c-ink-soft);
      text-decoration: none;
    }

    a.footer__link:hover {
      color: var(--c-accent);
      text-decoration: underline;
    }

    // A page that does not exist yet. Fainter, and with a default cursor: it should not
    // invite a click it cannot answer.
    .footer__link--soon {
      color: var(--c-ink-faint);
      cursor: default;
    }

    .footer__base {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      padding-block: var(--s-3);
      border-top: var(--divider);

      @media (min-width: t.$bp-md) {
        flex-direction: row;
        align-items: center;
        gap: var(--s-4);
      }
    }

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
  protected readonly columns = resolveFooter(FOOTER_COLUMNS, routes);

  /** Read once at construction. A page open across midnight on 31 December can cope. */
  protected readonly year = new Date().getFullYear();
}
