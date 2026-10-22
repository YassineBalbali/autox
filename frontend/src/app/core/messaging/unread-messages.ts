import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';

import { MessagesService } from '../api';
import { AuthService } from '../auth/auth.service';

/** How often the count is asked for while the tab is in view. */
export const UNREAD_POLL_MS = 60_000;

/**
 * How many conversations have a message the signed-in user has not opened: the number on
 * the header's "Messages" link.
 *
 * <p>Polled, once a minute and only while the tab is visible, and asked again as soon as it
 * becomes visible. There is no push channel yet, and a minute is the delay a person
 * waiting for an answer about a car tolerates; a hidden tab asking every minute all day
 * would be load for nobody. Screens that change the count — opening a conversation,
 * sending a message — call {@link refresh} rather than wait for the next tick.
 */
@Injectable({ providedIn: 'root' })
export class UnreadMessages {
  private readonly api = inject(MessagesService);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  private readonly current = signal(0);
  readonly count = this.current.asReadonly();

  constructor() {
    effect(() => {
      if (this.auth.isAuthenticated()) {
        this.refresh();
      } else {
        this.current.set(0);
      }
    });

    const visible = () => this.document.visibilityState !== 'hidden';
    const tick = () => {
      if (this.auth.isAuthenticated() && visible()) {
        this.refresh();
      }
    };
    const timer = setInterval(tick, UNREAD_POLL_MS);
    this.document.addEventListener('visibilitychange', tick);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      this.document.removeEventListener('visibilitychange', tick);
    });
  }

  refresh(): void {
    // A failure keeps the last count. The badge is a hint; an error banner in the header
    // for a missed poll would be noise, and the next tick tries again.
    this.api.unread().subscribe({
      next: (unread) => this.current.set(unread.conversations ?? 0),
      error: () => undefined,
    });
  }
}
