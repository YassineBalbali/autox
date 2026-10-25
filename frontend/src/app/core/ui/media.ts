import { DestroyRef, Signal, inject, signal } from '@angular/core';

/**
 * The breakpoints a component's code — not only its stylesheet — needs to know about:
 * where the markup itself differs, not just its layout. The same values as `$bp-sm` and
 * `$bp-lg` in `_tokens.scss`.
 */
export const PHONE = '(max-width: 479.98px)';
export const WIDE = '(min-width: 1024px)';

/**
 * A media query as a signal, kept current as the window is resized or turned.
 *
 * <p>Call it from an injection context: the listener is removed when the caller is
 * destroyed. Where the environment has no `matchMedia` — a test runner, a server render —
 * the answer is `fallback`, so the caller decides which layout it renders without one.
 */
export function mediaQuery(query: string, fallback: boolean): Signal<boolean> {
  const list = typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query) : null;
  const matches = signal(list ? list.matches : fallback);
  if (list) {
    const listener = (event: MediaQueryListEvent) => matches.set(event.matches);
    list.addEventListener('change', listener);
    inject(DestroyRef).onDestroy(() => list.removeEventListener('change', listener));
  }
  return matches.asReadonly();
}
