import { ChangeDetectionStrategy, Component, ViewEncapsulation, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * The address to write to about the site, the data it holds, or a listing. Shown on the legal
 * pages and the help pages. Null until the owner fills it in: every page then shows a marked
 * placeholder instead, so a missing address cannot pass for a real one.
 */
export const CONTACT_EMAIL: string | null = null;

/** When the texts of the legal pages were last revised. */
export const LAST_REVISED = '8 novembre 2026';

/** The contact address as a link, or the visible placeholder while there is none. */
@Component({
  selector: 'app-contact-address',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (email) {
      <a [href]="'mailto:' + email">{{ email }}</a>
    } @else {
      <mark class="info__placeholder" data-placeholder="contact">[adresse de contact à compléter]</mark>
    }
  `,
})
export class ContactAddress {
  protected readonly email = CONTACT_EMAIL;
}

/**
 * The frame of every page linked from the footer: a title, an introduction, the text, and for
 * the legal pages the date and the warning that the text is not legal advice.
 *
 * <p>Its styles are not encapsulated, under `.info`, because they are for the content each page
 * projects into it: headings, paragraphs, lists and tables written in that page's template.
 */
@Component({
  selector: 'app-info-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  template: `
    <article class="info">
      <header class="info__header">
        <h1 class="page-title">{{ heading() }}</h1>
        @if (lede()) {
          <p class="info__lede">{{ lede() }}</p>
        }
        @if (legal()) {
          <p class="meta">Dernière mise à jour : {{ revised }}</p>
        }
      </header>

      <div class="info__body">
        <ng-content />
      </div>

      @if (legal()) {
        <footer class="info__footer">
          <p class="info__disclaimer" data-disclaimer>Ce texte n’est pas un avis juridique.</p>
          <p class="meta">
            Voir aussi : <a routerLink="/mentions-legales">mentions légales</a> ·
            <a routerLink="/conditions-generales">conditions générales</a> ·
            <a routerLink="/confidentialite">politique de confidentialité</a> ·
            <a routerLink="/cookies">cookies</a>
          </p>
        </footer>
      }
    </article>
  `,
  styles: `
    @use '../../../styles/tokens' as t;

    .info {
      display: flex;
      flex-direction: column;
      gap: var(--s-4);
      max-width: 46rem;
      padding-block: var(--s-4) var(--s-5);
    }

    .info__header {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
    }

    .info__lede {
      color: var(--c-ink-soft);
      font-size: var(--t-md);
      line-height: var(--lh-snug);
    }

    .info__body {
      display: flex;
      flex-direction: column;
      gap: var(--s-3);
      line-height: var(--lh-body, 1.55);

      h2 {
        margin-top: var(--s-3);
      }

      h3 {
        margin-top: var(--s-1);
      }

      ul,
      ol {
        margin: 0;
        padding-left: 1.25rem;
        max-width: var(--w-prose);
        display: flex;
        flex-direction: column;
        gap: var(--s-1);
      }

      dl {
        margin: 0;
        display: grid;
        gap: var(--s-1) var(--s-3);

        @media (min-width: t.$bp-md) {
          grid-template-columns: 12rem minmax(0, 1fr);
        }
      }

      dt {
        font-weight: var(--fw-semibold);
      }

      dd {
        margin: 0;
      }

      code {
        font-size: 0.9em;
        overflow-wrap: anywhere;
      }
    }

    // A table that scrolls inside itself on a phone rather than widening the page.
    .info__table {
      overflow-x: auto;
      border: var(--divider);
      border-radius: var(--r-sm);

      table {
        width: 100%;
        min-width: 36rem;
        border-collapse: collapse;
        font-size: var(--t-sm);
      }

      th,
      td {
        padding: var(--s-1) var(--s-2);
        text-align: left;
        vertical-align: top;
        border-bottom: var(--divider);
      }

      tr:last-child td {
        border-bottom: 0;
      }

      th {
        background: var(--c-surface);
        font-weight: var(--fw-semibold);
      }
    }

    // What is not yet true, said where it applies: a gap, not small print.
    .info__gap {
      max-width: var(--w-prose);
      padding: var(--s-2) var(--s-3);
      background: var(--c-warn-soft);
      border-left: 3px solid var(--c-warn);
      border-radius: var(--r-sm);
    }

    .info__placeholder {
      padding: 0 var(--s-0, 2px);
      background: var(--c-warn-soft);
      color: var(--c-warn);
      font-weight: var(--fw-semibold);
    }

    .info__footer {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      padding-top: var(--s-3);
      border-top: var(--divider);
    }

    .info__disclaimer {
      font-weight: var(--fw-semibold);
    }
  `,
})
export class InfoPage {
  readonly heading = input.required<string>();
  readonly lede = input<string>('');
  /** A legal page: dated, with the not-legal-advice note and links to the others. */
  readonly legal = input(false);

  protected readonly revised = LAST_REVISED;
}
