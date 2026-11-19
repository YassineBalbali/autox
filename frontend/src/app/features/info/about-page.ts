import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Qui sommes-nous: one person and a portfolio project, said plainly. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-about-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="a-propos" />`,
})
export class AboutPage {}
