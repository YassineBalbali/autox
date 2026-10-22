import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ConversationSummary } from '../../core/api';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { Icon } from '../../shared/ui/icon';
import { sentAtFull, sentAtShort } from './messaging';

/**
 * One line of the inbox: who, about which car, the last thing said, and how many of their
 * messages wait to be read. Unread lines are in full ink and weight, as in every inbox.
 */
@Component({
  selector: 'app-inbox-item',
  imports: [RouterLink, SellerBadge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './inbox-item.html',
  styleUrl: './inbox-item.scss',
})
export class InboxItem {
  readonly item = input.required<ConversationSummary>();
  readonly selected = input(false);

  protected readonly unread = computed(() => this.item().unreadCount ?? 0);
  protected readonly when = computed(() => sentAtShort(this.item().lastMessageAt));
  protected readonly whenFull = computed(() => sentAtFull(this.item().lastMessageAt));

  /** "Vous : …", "Message masqué par la modération", or the other side's words. */
  protected readonly preview = computed(() => {
    const last = this.item().lastMessage;
    if (!last) {
      return '';
    }
    const text = last.hidden ? 'Message masqué par la modération' : (last.preview ?? '');
    return last.sentByMe ? `Vous : ${text}` : text;
  });

  protected readonly unreadLabel = computed(() =>
    this.unread() === 1 ? '1 message non lu' : `${this.unread()} messages non lus`,
  );
}
