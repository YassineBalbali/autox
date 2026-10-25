import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { UnreadMessages } from '../core/messaging/unread-messages';
import { Icon } from '../shared/ui/icon';

/**
 * The header's way to the inbox, with the number of conversations waiting for an answer —
 * conversations rather than messages, since that is what a person has to act on.
 *
 * <p>The number is repeated in words for a screen reader, which would otherwise announce
 * "Messages 2" with no idea what the 2 counts. Below the small breakpoint the word goes and
 * the icon and the number carry the link; its name stays, visually hidden.
 */
@Component({
  selector: 'app-messages-link',
  imports: [RouterLink, RouterLinkActive, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="link" routerLink="/messages" routerLinkActive="link--active">
      <app-icon name="message-circle" [size]="20" />
      <span class="link__label" i18n="@@headerMessages">Messages</span>
      @if (unread() > 0) {
        <span class="count-badge" aria-hidden="true">{{ unread() > 99 ? '99+' : unread() }}</span>
        <span class="visually-hidden">{{ unreadLabel() }}</span>
      }
    </a>
  `,
  styles: `
    @use '../../styles/tokens' as t;

    .link {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--s-2);
      color: var(--c-ink);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      text-decoration: none;
      white-space: nowrap;

      &:hover {
        color: var(--c-accent);
      }

      // A finger's target, even when the icon alone is showing.
      @include t.touch {
        min-width: 44px;
        min-height: 44px;
      }
    }

    .link--active {
      color: var(--c-accent);
    }

    .link__label {
      @media (max-width: calc(t.$bp-sm - 1px)) {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
    }
  `,
})
export class MessagesLink {
  protected readonly unread = inject(UnreadMessages).count;

  /** ", 2 conversations non lues": read after the link's own word. */
  protected readonly unreadLabel = computed(() => {
    const count = this.unread();
    return count === 1 ? ', 1 conversation non lue' : `, ${count} conversations non lues`;
  });
}
