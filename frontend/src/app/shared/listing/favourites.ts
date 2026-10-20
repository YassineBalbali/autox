import { Injectable, computed, signal } from '@angular/core';

const STORAGE_KEY = 'autox.favourites';

/**
 * Most favourites one browser keeps. Far above what anyone saves by hand; it exists so a
 * script or a stuck button cannot grow the stored entry without bound.
 */
export const FAVOURITES_LIMIT = 200;

/**
 * The listings a visitor has marked with the heart, in this browser.
 *
 * <p>Per browser and nothing more: there is no account behind it and nothing is sent to
 * the API. It is the interface of a feature, kept honest — the heart remembers what was
 * marked, on this device, until the browser's storage is cleared — not a saved-listings
 * service, which would need the backend.
 *
 * <p>Storage may be missing or refuse writes (a private window, blocked site data). The
 * hearts then still work for the visit; they are only not remembered.
 */
@Injectable({ providedIn: 'root' })
export class Favourites {
  private readonly saved = signal<readonly string[]>(read());

  /** Most recently marked first. */
  readonly ids = this.saved.asReadonly();

  private readonly lookup = computed(() => new Set(this.saved()));

  has(id: string): boolean {
    return this.lookup().has(id);
  }

  toggle(id: string): void {
    const current = this.saved();
    const next = current.includes(id)
      ? current.filter((saved) => saved !== id)
      : [id, ...current].slice(0, FAVOURITES_LIMIT);
    this.saved.set(next);
    write(next);
  }
}

function read(): readonly string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
    // Whatever is stored was written by an earlier version of this code, or by hand:
    // anything that is not a list of strings is ignored rather than trusted.
    return Array.isArray(parsed)
      ? parsed.filter((id): id is string => typeof id === 'string').slice(0, FAVOURITES_LIMIT)
      : [];
  } catch {
    return [];
  }
}

function write(ids: readonly string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(ids));
  } catch {
    // Not remembered, still marked. See the class comment.
  }
}
