import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Signaler une annonce: where the button is, what happens next. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-report-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="signalement" />`,
})
export class ReportPage {}
