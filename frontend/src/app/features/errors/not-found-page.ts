import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';

import { NotFound } from '../../shared/ui/not-found';

/**
 * The page for an address that matches no route.
 *
 * <p>It replaces a redirect to the home page. Sending an unknown address to the search
 * results was tidy and wrong: the visitor typed or followed one thing and was shown
 * another, with nothing saying why, and a mistyped link looked as though it worked.
 */
@Component({
  selector: 'app-not-found-page',
  imports: [NotFound],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-not-found />`,
})
export class NotFoundPage {
  constructor() {
    inject(Title).setTitle('Page introuvable · AutoX');
  }
}
