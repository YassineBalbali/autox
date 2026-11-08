import { Injectable, signal } from '@angular/core';

/** How the moderation lists are laid out. The values are the words on the toggle. */
export type ModerationLayout = 'cartes' | 'liste';

export const DEFAULT_LAYOUT: ModerationLayout = 'cartes';

/** The `localStorage` key. Listed on the cookies page, whose spec reads it from here. */
export const MODERATION_LAYOUT_STORAGE_KEY = 'autox.admin.moderation.layout';

/** A page of cards holds whole listings; a page of rows is for finding one. */
export const PAGE_SIZES: Readonly<Record<ModerationLayout, number>> = { cartes: 10, liste: 25 };

/**
 * Whether this administrator reads the moderation lists as cards or as rows.
 *
 * <p>A preference rather than part of what is being looked at, for the reason the search page's
 * view is (`ViewPreference`): it is not in the URL, so a link sent to a colleague opens in their
 * own choice. Kept in `localStorage`, and optional throughout: where storage is blocked the toggle
 * still works for the visit.
 */
@Injectable({ providedIn: 'root' })
export class ModerationLayoutPreference {
  private readonly current = signal<ModerationLayout>(read());

  readonly layout = this.current.asReadonly();

  set(layout: ModerationLayout): void {
    this.current.set(layout);
    try {
      localStorage.setItem(MODERATION_LAYOUT_STORAGE_KEY, layout);
    } catch {
      // Not remembered; the layout asked for is on screen all the same.
    }
  }
}

function read(): ModerationLayout {
  try {
    const stored = localStorage.getItem(MODERATION_LAYOUT_STORAGE_KEY);
    return stored === 'cartes' || stored === 'liste' ? stored : DEFAULT_LAYOUT;
  } catch {
    return DEFAULT_LAYOUT;
  }
}
