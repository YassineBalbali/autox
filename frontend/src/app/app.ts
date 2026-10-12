import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { SiteFooter } from './layout/site-footer';
import { SiteHeader } from './layout/site-header';

/**
 * The application shell: skip link, header, the routed page, footer.
 *
 * <p>It holds no state of its own. The header reads the session it needs directly, which
 * keeps the shell from becoming the place every piece of global state accumulates.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SiteHeader, SiteFooter],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
