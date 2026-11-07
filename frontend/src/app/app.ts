import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { SiteFooter } from './layout/site-footer';
import { SiteHeader } from './layout/site-header';

/** Whether a URL is in the back office, which has a frame of its own under the header. */
export function isBackOffice(url: string): boolean {
  return /^\/admin(?:[/?#]|$)/.test(url);
}

/**
 * The application shell: skip link, header, the routed page, footer.
 *
 * <p>It holds no state of its own beyond where the router is. The header reads the session it
 * needs directly, which keeps the shell from becoming the place every piece of global state
 * accumulates. The back office gets the whole width under the header and no public footer:
 * its sidebar runs down the window's left edge.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SiteHeader, SiteFooter],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);

  protected readonly backOffice = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map((event) => isBackOffice(event.urlAfterRedirects)),
    ),
    { initialValue: false },
  );
}
