import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** What an open overlay holds: its way of closing on Back, and the history entry it is on. */
interface Overlay {
  readonly onBack: () => void;
  /** The address when it opened: if it is still the address when it closes, nothing moved. */
  readonly openedAt: string;
  /** The top history entry while it is open — updated as filters inside it navigate. */
  latest: { state: unknown; href: string };
}

/** The handle an overlay keeps while it is open. */
export interface OverlayEntry {
  /**
   * The overlay closed from its own controls — a close button, the backdrop, Escape. The
   * history entry it added is taken back, unless the address moved while it was open: then
   * that entry is the record of the change, and stays, so Back undoes it.
   */
  closed(): void;
  /** The overlay closed because the page moved on: the entry is the navigation's now. */
  release(): void;
}

/**
 * Back closes an open overlay — the phone's menu drawer, the filter sheet — and does
 * nothing else.
 *
 * <p>On a phone, Back is the way out of whatever is on top. Without this it would also
 * act on the page underneath: leave it, or, for the filter sheet, undo the last filter
 * behind the sheet where nobody can see it happen.
 *
 * <p>How: opening an overlay adds a history entry at the same address. Back returns to
 * the entry under it, and the `popstate` that says so is caught here before the router
 * hears it, and stopped. The router never starts a navigation, so nothing on the page
 * changes but the overlay closing.
 *
 * <p>"Before the router" is a matter of order: listeners on the window run in the order
 * they were added, whether or not they capture. So the listener is added when this file is
 * loaded — which is before the application starts, and so before the router adds its own —
 * and hands the event to the service once it exists.
 *
 * <p>The filter sheet navigates while it is open, replacing its entry as it goes. So when
 * Back lands on the entry under it, that entry may hold the address from before the sheet
 * opened; the latest one is put back on top, and the address bar, the router and the
 * results stay where they were.
 */
@Injectable({ providedIn: 'root' })
export class OverlayHistory {
  /** The instance the window listener below hands events to. */
  static current: OverlayHistory | null = null;

  private top: Overlay | null = null;
  /** Backs this service started itself, to take an entry away: not the visitor's. */
  private ownBacks = 0;

  constructor() {
    OverlayHistory.current = this;

    inject(Router)
      .events.pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => {
        if (this.top) {
          this.top.latest = { state: history.state, href: location.href };
        }
      });
  }

  /** Adds the history entry for an overlay that has just opened. */
  open(onBack: () => void): OverlayEntry {
    history.pushState(history.state, '', location.href);
    const overlay: Overlay = {
      onBack,
      openedAt: location.href,
      latest: { state: history.state, href: location.href },
    };
    this.top = overlay;

    return {
      closed: () => {
        if (this.top !== overlay) {
          return;
        }
        this.top = null;
        if (location.href === overlay.openedAt) {
          this.ownBacks++;
          history.back();
        }
      },
      release: () => {
        if (this.top === overlay) {
          this.top = null;
        }
      },
    };
  }

  /** @internal Called by the window listener, first of all popstate listeners. */
  onPopState(event: PopStateEvent): void {
    if (this.ownBacks > 0) {
      this.ownBacks--;
      event.stopImmediatePropagation();
      return;
    }
    const overlay = this.top;
    if (!overlay) {
      return;
    }
    this.top = null;
    event.stopImmediatePropagation();
    if (location.href !== overlay.latest.href) {
      // Back went under where the overlay left the page: put that entry back.
      history.pushState(overlay.latest.state, '', overlay.latest.href);
    }
    overlay.onBack();
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('popstate', (event) => OverlayHistory.current?.onPopState(event));
}
