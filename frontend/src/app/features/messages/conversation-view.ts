import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { ConversationDetail, MessagesService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { UnreadMessages } from '../../core/messaging/unread-messages';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { Price } from '../../shared/listing/price';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { Inbox } from './inbox';
import { MessageThread } from './message-thread';
import { MESSAGE_MAX_LENGTH, sendFailure } from './messaging';

/**
 * One conversation: the listing it is about, the messages, and the box to answer in.
 *
 * <p>Opening it marks it read — a separate call, so the API's GET stays free of side
 * effects — and the list beside it and the header's count follow at once. When the list
 * shows a newer last message than the one on screen, something arrived: it is fetched
 * again, and marked read again.
 */
@Component({
  selector: 'app-conversation-view',
  imports: [RouterLink, Price, SellerBadge, Alert, Icon, MessageThread],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './conversation-view.html',
  styleUrl: './conversation-view.scss',
})
export class ConversationView implements OnDestroy {
  private readonly api = inject(MessagesService);
  private readonly errors = inject(ErrorMessageService);
  private readonly inbox = inject(Inbox);
  private readonly unread = inject(UnreadMessages);

  /** The conversation's id, from the route. */
  readonly id = input.required<string>();

  protected readonly conversation = signal<ConversationDetail | null>(null);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly loadFailure = signal<string | null>(null);

  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal<string | null>(null);
  protected readonly maxLength = MESSAGE_MAX_LENGTH;
  protected readonly canSend = computed(
    () => this.draft().trim().length > 0 && this.draft().length <= MESSAGE_MAX_LENGTH && !this.sending(),
  );

  private readonly minorUnits = toSignal(inject(ReferenceStore).minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });
  protected readonly minorUnit = computed(
    () => this.minorUnits()[this.conversation()?.listing?.currencyCode ?? ''] ?? DEFAULT_MINOR_UNIT,
  );

  private readonly thread = viewChild<ElementRef<HTMLElement>>('thread');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.open(id));
    });

    effect(() => {
      const listed = this.inbox.items().find((item) => item.id === this.id());
      const shown = untracked(() => this.conversation()?.messages?.at(-1)?.sentAt);
      if (listed?.lastMessageAt && shown && listed.lastMessageAt > shown && untracked(() => this.state()) === 'ready') {
        untracked(() => this.open(this.id(), true));
      }
    });

    // The newest message at the bottom, in view, after every change to what is shown.
    afterRenderEffect(() => {
      this.conversation();
      const thread = this.thread()?.nativeElement;
      if (thread) {
        thread.scrollTop = thread.scrollHeight;
      }
    });
  }

  ngOnDestroy(): void {
    this.inbox.selectedId.set(null);
  }

  protected edit(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Ctrl+Entrée or ⌘+Entrée sends; Entrée alone is a new line, as in a long message it should be. */
  protected keydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      this.send(event);
    }
  }

  protected send(event: Event): void {
    event.preventDefault();
    if (!this.canSend()) {
      return;
    }
    this.sending.set(true);
    this.sendError.set(null);
    this.api.reply({ id: this.id(), sendMessageRequest: { body: this.draft().trim() } }).subscribe({
      next: (conversation) => {
        this.sending.set(false);
        this.draft.set('');
        this.conversation.set(conversation);
        this.inbox.sent(conversation);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.sendError.set(sendFailure(error, this.errors));
      },
    });
  }

  protected reported(messageId: string): void {
    this.conversation.update((conversation) =>
      conversation
        ? {
            ...conversation,
            messages: conversation.messages?.map((message) =>
              message.id === messageId ? { ...message, reportedByMe: true } : message,
            ),
          }
        : conversation,
    );
  }

  private open(id: string, quietly = false): void {
    this.inbox.selectedId.set(id);
    if (!quietly) {
      this.state.set('loading');
      this.conversation.set(null);
      this.sendError.set(null);
    }
    this.api.conversation({ id }).subscribe({
      next: (conversation) => {
        if (id !== this.id()) {
          return;
        }
        this.conversation.set(conversation);
        this.state.set('ready');
        this.api.markRead({ id }).subscribe({
          next: () => {
            this.inbox.markRead(id);
            this.unread.refresh();
          },
          error: () => undefined,
        });
      },
      error: (error: unknown) => {
        if (id !== this.id() || quietly) {
          return;
        }
        this.loadFailure.set(this.errors.translate(error).message);
        this.state.set('error');
      },
    });
  }
}
