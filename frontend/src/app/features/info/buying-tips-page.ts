import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Conseils pour acheter: general advice, and how the site's own tools help. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-buying-tips-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="conseils-achat" />`,
})
export class BuyingTipsPage {}
