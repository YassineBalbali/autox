import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Centre d'aide: the questions the site actually raises, answered from what it does. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-help-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="aide" />`,
})
export class HelpPage {}
