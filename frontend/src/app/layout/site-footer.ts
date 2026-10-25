import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterLink, Routes } from '@angular/router';

import { routes } from '../app.routes';
import { Logo } from '../shared/brand/logo';
import { Icon } from '../shared/ui/icon';

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
    // The pages that exist today, first: until the others do, this is the column that
    // takes the reader somewhere.
    title: 'AutoX',
    items: [
      { label: 'Voitures d’occasion', path: 'recherche' },
      { label: 'Déposer une annonce', path: 'vendre' },
      { label: 'Mes annonces', path: 'mes-annonces' },
    ],
  },
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
 *
 * <p>On a phone the five columns are seventeen links stacked one under another — a screen
 * and a half of small print to scroll past. Below the medium breakpoint each column folds
 * to its title, a button that opens it: the standard accordion, a heading holding a
 * button with aria-expanded and aria-controls. Above it there is nothing to open, so the
 * title is the plain heading again rather than a button that does nothing.
 */
@Component({
  selector: 'app-site-footer',
  imports: [Logo, Icon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <nav class="footer__columns" aria-label="Pied de page" i18n-aria-label="@@footerLabel">
        @for (column of columns; track column.title; let i = $index) {
          <section class="footer__column">
            <h2 class="footer__title">
              <span class="footer__title-text">{{ column.title }}</span>
              <button
                class="footer__toggle"
                type="button"
                [attr.aria-expanded]="isOpen(i)"
                [attr.aria-controls]="'footer-list-' + i"
                (click)="toggle(i)"
              >
                {{ column.title }}
                <app-icon name="chevron-down" [size]="18" />
              </button>
            </h2>
            <ul class="footer__list" [id]="'footer-list-' + i" [class.footer__list--open]="isOpen(i)">
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

      <!-- Said on every page, in words, not implied by the look of the listings: a
           visitor arriving from a link has no other way to know. role="note" so that it
           is announced as an aside rather than read as part of the navigation. -->
      <p class="footer__demo" role="note">
        <strong i18n="@@footerDemoTitle">Site de démonstration.</strong>
        <ng-container i18n="@@footerDemoBody">
          Les annonces, les vendeurs et les prix sont fictifs : aucun de ces véhicules n’est à vendre.
          Les comptes créés ici ne sont pas destinés à un usage réel et peuvent être supprimés à tout
          moment ; n’y saisissez ni donnée personnelle, ni mot de passe utilisé ailleurs.
        </ng-container>
      </p>

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

    // One column of folded sections on a phone; the grid from the medium breakpoint up.
    .footer__columns {
      display: flex;
      flex-direction: column;
      padding-block: var(--s-2) var(--s-4);

      @media (min-width: t.$bp-md) {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: var(--s-5);
        padding-block: var(--s-5) var(--s-4);
      }

      @media (min-width: t.$bp-lg) {
        grid-template-columns: repeat(5, minmax(0, 1fr));
      }
    }

    .footer__column {
      @media (max-width: calc(t.$bp-md - 0.02px)) {
        border-bottom: var(--divider);
      }
    }

    .footer__title {
      font-size: var(--t-xs);
      font-weight: var(--fw-bold);
      color: var(--c-ink);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    // ---- phone: the title is a button, the list is folded --------------------------

    .footer__title-text {
      display: none;
    }

    .footer__toggle {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      min-height: 48px;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: left;
      text-transform: inherit;
      letter-spacing: inherit;

      app-icon {
        color: var(--c-ink-faint);
        transition: transform 150ms ease-out;
      }

      &[aria-expanded='true'] app-icon {
        transform: rotate(180deg);
      }
    }

    .footer__list {
      display: none;
      flex-direction: column;
      margin: 0;
      padding: 0 0 var(--s-2);
      list-style: none;
    }

    .footer__list--open {
      display: flex;
    }

    // ---- from the medium breakpoint: a heading, and every list open ----------------

    @media (min-width: t.$bp-md) {
      .footer__title {
        margin-bottom: var(--s-2);
      }

      .footer__title-text {
        display: inline;
      }

      .footer__toggle {
        display: none;
      }

      .footer__list {
        display: flex;
        gap: var(--s-1);
        padding: 0;
      }
    }

    .footer__link {
      // A full-width 44px row on a phone, so a finger cannot land between two entries.
      display: flex;
      align-items: center;
      min-height: 44px;
      font-size: var(--t-body);
      line-height: var(--lh-snug);
      color: var(--c-ink-soft);
      text-decoration: none;

      @media (min-width: t.$bp-md) {
        display: inline;
        min-height: 0;
        font-size: var(--t-sm);
      }
    }

    a.footer__link {
      color: var(--c-ink);
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

    // The one place the footer raises its voice. The warning colours, because that is
    // what it is; a rule on the leading edge like the alert banner, so it reads as a notice
    // and not as a paragraph of small print.
    .footer__demo {
      // Overrides the global reading measure on p.
      max-width: none;
      margin-bottom: var(--s-3);
      padding: var(--s-2) var(--s-3);
      background: var(--c-warn-soft);
      border-left: 3px solid var(--c-warn);
      border-radius: var(--r-sm);
      color: var(--c-warn);
      font-size: var(--t-sm);
      line-height: var(--lh-snug);
    }

    .footer__base {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      // The mark, the line and the copyright on one left edge, not the mark centred.
      align-items: flex-start;
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

  /** The columns unfolded on a phone, by position. All folded to begin with. */
  private readonly opened = signal<ReadonlySet<number>>(new Set());

  protected isOpen(index: number): boolean {
    return this.opened().has(index);
  }

  protected toggle(index: number): void {
    this.opened.update((open) => {
      const next = new Set(open);
      if (!next.delete(index)) next.add(index);
      return next;
    });
  }

  /** Read once at construction. A page open across midnight on 31 December can cope. */
  protected readonly year = new Date().getFullYear();
}
