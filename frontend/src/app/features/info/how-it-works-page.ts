import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Comment ça marche: the site's real features, for a buyer and for a seller. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-how-it-works-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="comment-ca-marche" />`,
})
export class HowItWorksPage {}
