import { Injectable, signal } from '@angular/core';

/**
 * One message handed from the screen that caused it to the screen that shows it.
 *
 * <p>For the outcomes that are reported after a navigation: a password reset confirmed on
 * the sign-in page, a sign-out confirmed on the home page. The screen that knows the
 * outcome is not the screen the user is about to be looking at, so the message has to
 * survive exactly one route change and no more.
 *
 * <p>Not a query parameter, which was the alternative. A `?message=` is in the address bar,
 * so it survives a reload and a bookmark — a user who refreshes is told again that their
 * password was reset, and a shared link tells someone else. It is also, if the text comes
 * from the parameter rather than from a code, a way to put chosen words on our own page.
 *
 * <p>Read with {@link take}, which clears it: a message left in place would reappear the
 * next time the same screen was visited.
 */
@Injectable({ providedIn: 'root' })
export class FlashService {
  private readonly pending = signal<string | null>(null);

  /** Queues a message for the next screen. In-memory, so a reload drops it, as intended. */
  set(message: string): void {
    this.pending.set(message);
  }

  /** The queued message, once. */
  take(): string | null {
    const message = this.pending();
    if (message !== null) {
      this.pending.set(null);
    }
    return message;
  }
}
