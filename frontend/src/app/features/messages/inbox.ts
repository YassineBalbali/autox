import { Injectable, inject, signal } from '@angular/core';

import { ConversationDetail, ConversationSummary, MessagesService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';

/** Conversations fetched per page of the list. */
export const INBOX_PAGE_SIZE = 20;

/**
 * The conversation list, shared by the list and the open conversation beside it.
 *
 * <p>Provided by the messages page, not the root: it lives as long as the screen and is
 * gone when the user leaves it. The open conversation updates it rather than the list
 * refetching — opening one clears its unread count, sending moves it to the top — so the
 * two halves of the screen never disagree.
 */
@Injectable()
export class Inbox {
  private readonly api = inject(MessagesService);
  private readonly errors = inject(ErrorMessageService);

  readonly items = signal<ConversationSummary[]>([]);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly failure = signal<string | null>(null);
  readonly hasMore = signal(false);
  readonly loadingMore = signal(false);
  /** The conversation open beside the list, for highlighting it. */
  readonly selectedId = signal<string | null>(null);

  private pages = 1;

  /**
   * Fetches as many conversations as are already shown, from the top: a refresh, after new
   * messages arrived, keeps the list as long as the user had scrolled it.
   */
  load(): void {
    const size = Math.min(50, this.pages * INBOX_PAGE_SIZE);
    this.api.inbox({ page: 0, size }).subscribe({
      next: (page) => {
        this.items.set(page.items ?? []);
        this.hasMore.set(!!page.hasNext);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        // A failed refresh of a list already on screen keeps the list.
        if (this.state() !== 'ready') {
          this.failure.set(this.errors.translate(error).message);
          this.state.set('error');
        }
      },
    });
  }

  loadMore(): void {
    if (this.loadingMore() || !this.hasMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.api.inbox({ page: this.pages, size: INBOX_PAGE_SIZE }).subscribe({
      next: (page) => {
        const known = new Set(this.items().map((item) => item.id));
        this.items.update((items) => [...items, ...(page.items ?? []).filter((item) => !known.has(item.id))]);
        this.hasMore.set(!!page.hasNext);
        this.pages += 1;
        this.loadingMore.set(false);
      },
      error: () => this.loadingMore.set(false),
    });
  }

  /** The user has opened it: nothing in it is unread any more. */
  markRead(id: string): void {
    this.items.update((items) => items.map((item) => (item.id === id ? { ...item, unreadCount: 0 } : item)));
  }

  /** A message was just sent in it: it moves to the top with that message as its last. */
  sent(conversation: ConversationDetail): void {
    const last = conversation.messages?.at(-1);
    const existing = this.items().find((item) => item.id === conversation.id);
    if (!existing || !last) {
      this.load();
      return;
    }
    const updated: ConversationSummary = {
      ...existing,
      unreadCount: 0,
      canReply: conversation.canReply,
      lastMessageAt: last.sentAt,
      lastMessage: { preview: last.body, sentByMe: true, hidden: false, sentAt: last.sentAt },
    };
    this.items.update((items) => [updated, ...items.filter((item) => item.id !== conversation.id)]);
  }
}
