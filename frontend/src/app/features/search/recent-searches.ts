import { Injectable, computed, signal } from '@angular/core';
import { Params, convertToParamMap } from '@angular/router';

import { DEFAULT_SORT, SearchQuery, activeFilterCount, parseQuery, toQueryParams } from './search-query';

const KEY = 'autox.search.recent';
/** How many the home page shows, and so how many are worth keeping. */
export const RECENT_LIMIT = 3;

/**
 * The visitor's last few searches, so the home page can offer them back.
 *
 * <p>Kept in `localStorage`: per browser, not per account, and never sent anywhere. That
 * is the right scope for "what was I looking at" — it needs no sign-in, and it is gone
 * with the browser's site data, which is where a visitor expects it to be.
 *
 * <p>Stored as query parameters, the same form as a search link, and read back through
 * {@link parseQuery}. So an entry is exactly as trustworthy as a URL — hand-edited
 * storage gets the same bounding and dropping a hand-edited address does — and an entry
 * saved by an older version of the site still opens the search it named, because the
 * parameter names are a contract that does not change.
 *
 * <p>Only searches with at least one filter are kept. "Everything, newest first" is the
 * results page itself, not a search anybody would want to return to. Sort order is not
 * part of an entry: a search re-sorted is the same search.
 */
@Injectable({ providedIn: 'root' })
export class RecentSearches {
  private readonly stored = signal<readonly SearchQuery[]>(read());

  /** Newest first. */
  readonly entries = this.stored.asReadonly();
  readonly isEmpty = computed(() => this.stored().length === 0);

  /** Puts a search at the front, moving it there if it was already in the list. */
  record(query: SearchQuery): void {
    const entry = { ...query, sort: DEFAULT_SORT };
    if (activeFilterCount(entry) === 0) {
      return;
    }
    const id = idOf(entry);
    const next = [entry, ...this.stored().filter((existing) => idOf(existing) !== id)].slice(0, RECENT_LIMIT);
    this.stored.set(next);
    write(next);
  }

  remove(query: SearchQuery): void {
    const id = idOf(query);
    const next = this.stored().filter((existing) => idOf(existing) !== id);
    this.stored.set(next);
    write(next);
  }
}

/** Two entries are the same search when their links are the same. */
export function idOf(query: SearchQuery): string {
  return JSON.stringify(toQueryParams({ ...query, sort: DEFAULT_SORT }));
}

function read(): SearchQuery[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]');
    if (!Array.isArray(parsed)) {
      return [];
    }
    const seen = new Set<string>();
    const entries: SearchQuery[] = [];
    for (const raw of parsed) {
      if (!isParams(raw)) {
        continue;
      }
      const query = { ...parseQuery(convertToParamMap(raw)), sort: DEFAULT_SORT };
      const id = idOf(query);
      if (activeFilterCount(query) > 0 && !seen.has(id)) {
        seen.add(id);
        entries.push(query);
      }
    }
    return entries.slice(0, RECENT_LIMIT);
  } catch {
    // Storage blocked (private browsing, a sandboxed frame) or not JSON: no history.
    return [];
  }
}

function write(entries: readonly SearchQuery[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(entries.map((entry) => toQueryParams(entry))));
  } catch {
    // Full or blocked. The list still works for this visit; it just will not outlive it.
  }
}

function isParams(value: unknown): value is Params {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((entry) => typeof entry === 'string')
  );
}
