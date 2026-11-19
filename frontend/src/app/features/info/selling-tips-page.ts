import { ChangeDetectionStrategy, Component } from '@angular/core';

import { GuidePage } from './guide-page';

/** Conseils pour vendre: a good listing, as the editor and moderation see one. Its text is in frontend/content/help.fr.json. */
@Component({
  selector: 'app-selling-tips-page',
  imports: [GuidePage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-guide-page guide="conseils-vente" />`,
})
export class SellingTipsPage {}
