import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Router } from '@angular/router';

/**
 * "This is not here" — for a listing that has gone, and for an address that never was.
 *
 * <p>One component for both, because the visitor's situation is the same: they followed
 * something that led nowhere, and what they need is to be told so plainly and offered the
 * way to what is here. What it must not do is look like a fault. No red, no "erreur":
 * nothing broke, and a listing being sold is the marketplace working.
 *
 * <p>The heading is an `h1`. This replaces the page's content, so it is the page's title
 * as far as a screen reader's heading list is concerned.
 */
@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="panel missing">
      <p class="missing__code" aria-hidden="true">404</p>
      <h1 class="missing__title">{{ heading() }}</h1>
      <p class="lede">{{ message() }}</p>
      <div class="form-actions">
        <a class="btn btn--primary" [href]="linkUrl()" (click)="follow($event)">{{ linkLabel() }}</a>
      </div>
    </div>
  `,
  styles: `
    .missing {
      display: flex;
      flex-direction: column;
      gap: var(--s-2);
      max-width: var(--w-prose);
      margin-inline: auto;
      padding-block: var(--s-6);
    }

    // The number, quietly: it tells someone who knows what a 404 is that this is one,
    // without being the first thing everyone else reads.
    .missing__code {
      color: var(--c-ink-faint);
      font-size: var(--t-sm);
      font-weight: var(--fw-bold);
      letter-spacing: 0.08em;
    }

    .missing__title {
      font-size: var(--t-xl);
    }
  `,
})
export class NotFound {
  readonly heading = input('Cette page n’existe pas');
  readonly message = input('L’adresse est peut-être incomplète, ou la page a été déplacée.');
  /**
   * Where the link leads: a URL, which may carry a query string.
   *
   * <p>A URL and not a `routerLink`, and the difference is not cosmetic. The listing page
   * passes the visitor's last search here — `/?marque=peugeot` — and `routerLink` given a
   * string treats all of it as a path: the "?" is percent-encoded and the link goes to
   * `/%3Fmarque%3Dpeugeot`, which matches nothing. So the `href` is the URL as written,
   * and a plain click is handed to the router as a URL.
   */
  readonly linkUrl = input('/recherche');
  readonly linkLabel = input('Voir les annonces');

  private readonly router = inject(Router);

  protected follow(event: MouseEvent): void {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
      // A new tab or window was asked for: the href does that.
      return;
    }
    event.preventDefault();
    void this.router.navigateByUrl(this.linkUrl());
  }
}
