import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

import { UnreadMessages } from '../../core/messaging/unread-messages';
import { trackKeyboardInset } from '../../core/ui/keyboard-inset';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { Inbox } from './inbox';
import { InboxItem } from './inbox-item';

/**
 * The inbox: every conversation the user has, as a buyer and as a seller, with the open one
 * beside the list.
 *
 * <p>The list is this component and the conversation is a child route, so moving from one
 * conversation to the next replaces only the right-hand side: the list keeps its place and
 * does not refetch. Below the medium breakpoint there is room for one of the two, so the
 * list shows until a conversation is opened, and the conversation has a way back.
 *
 * <p>The list is fetched again whenever the unread count in the header changes — that is
 * the sign that something arrived — rather than on its own timer.
 */
@Component({
  selector: 'app-messages-page',
  imports: [RouterLink, RouterOutlet, Alert, Icon, InboxItem],
  providers: [Inbox],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './messages-page.html',
  styleUrl: './messages-page.scss',
})
export class MessagesPage {
  protected readonly inbox = inject(Inbox);
  private readonly unread = inject(UnreadMessages);

  constructor() {
    // The keyboard's height, for the conversation screen's composer on iOS.
    trackKeyboardInset();

    effect(() => {
      this.unread.count();
      this.inbox.load();
    });
  }
}
