import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Espace professionnel: what a dealer account is on AutoX, and nothing it is not. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-pro-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="pro" />`,
})
export class ProPage {}
