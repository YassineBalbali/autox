import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { Icon } from '../../../shared/ui/icon';
import { IconName } from '../../../shared/ui/icons.data';

/** What a panel shows when there is nothing to count: an icon and a sentence, never an empty chart. */
@Component({
  selector: 'app-empty-state',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.small]': 'small()' },
  template: `
    <app-icon [name]="icon()" [size]="small() ? 22 : 28" />
    <p><ng-content /></p>
  `,
  styles: `
    :host {
      display: grid;
      justify-items: center;
      gap: var(--s-2);
      padding: var(--s-5) var(--s-3);
      border: 1px dashed var(--c-line-strong);
      border-radius: var(--r-lg);
      color: var(--c-ink-faint);
      text-align: center;
    }

    :host(.small) {
      padding: var(--s-3);
    }

    p {
      max-width: 22rem;
      margin: 0;
      font-size: var(--t-sm);
      color: var(--c-ink-soft);
    }
  `,
})
export class EmptyState {
  readonly icon = input.required<IconName>();
  readonly small = input(false);
}
