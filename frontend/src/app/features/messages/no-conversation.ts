import { ChangeDetectionStrategy, Component } from '@angular/core';

import { Icon } from '../../shared/ui/icon';

/** The right-hand pane before a conversation is chosen. Never seen on a phone, where the list has the screen. */
@Component({
  selector: 'app-no-conversation',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="none">
      <app-icon name="message-circle" [size]="36" />
      <p i18n="@@messagesChoose">Choisissez une conversation pour la lire et y répondre.</p>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex: 1;
    }

    .none {
      display: grid;
      flex: 1;
      place-content: center;
      justify-items: center;
      gap: var(--s-2);
      background: var(--c-page);
      color: var(--c-ink-faint);

      p {
        margin: 0;
      }
    }
  `,
})
export class NoConversation {}
