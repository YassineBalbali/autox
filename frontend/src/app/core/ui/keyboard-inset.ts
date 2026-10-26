import { DestroyRef, inject } from '@angular/core';

/**
 * Keeps two custom properties on the root element current while the caller lives:
 * `--keyboard`, how much of the bottom of the window the on-screen keyboard covers, and
 * `--viewport-top`, how far the browser has panned the visible part of the page down.
 *
 * <p>For a screen fixed to the window with something at its foot that must stay above the
 * keyboard — a message composer. On Android the viewport meta's
 * `interactive-widget=resizes-content` already shrinks the window when the keyboard opens,
 * and both values stay at zero. iOS Safari lets the keyboard cover the window instead and
 * pans what is visible; only `visualViewport` says by how much, which is what this reads.
 *
 * <p>Call it from an injection context. Without `visualViewport` — a test runner, an old
 * browser — it does nothing, and the properties' fallbacks apply.
 */
export function trackKeyboardInset(): void {
  const viewport = typeof window !== 'undefined' ? window.visualViewport : null;
  if (!viewport) {
    return;
  }
  const root = document.documentElement;
  const update = () => {
    const covered = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
    root.style.setProperty('--keyboard', `${Math.round(covered)}px`);
    root.style.setProperty('--viewport-top', `${Math.round(viewport.offsetTop)}px`);
  };
  viewport.addEventListener('resize', update);
  viewport.addEventListener('scroll', update);
  update();
  inject(DestroyRef).onDestroy(() => {
    viewport.removeEventListener('resize', update);
    viewport.removeEventListener('scroll', update);
    root.style.removeProperty('--keyboard');
    root.style.removeProperty('--viewport-top');
  });
}
