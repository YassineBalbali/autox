import { afterAll, beforeAll, onTestFinished } from 'vitest';

/**
 * What jsdom leaves out, for the specs of components that use it. Imported by specs only.
 */

/**
 * The modal methods of `<dialog>`, which jsdom declares but does not implement: showModal
 * opens it, close shuts it and fires `close` — which is also what a browser does on Escape.
 * Call at the top of a `describe`.
 */
export function stubDialogs(): void {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  const original = { showModal: proto['showModal'], close: proto['close'] };

  beforeAll(() => {
    proto['showModal'] = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    proto['close'] = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    };
  });

  afterAll(() => {
    proto['showModal'] = original.showModal;
    proto['close'] = original.close;
  });
}

/**
 * Gives jsdom a `matchMedia` that answers from a function of the query, until the end of
 * the current test. Call it before the component is created: it reads the media once, then
 * listens.
 */
export function stubMedia(matches: (query: string) => boolean): void {
  const target = window as unknown as Record<string, unknown>;
  const original = target['matchMedia'];
  target['matchMedia'] = (query: string) => ({
    matches: matches(query),
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  });
  onTestFinished(() => {
    target['matchMedia'] = original;
  });
}

/** A phone-width screen: below every breakpoint. */
export const PHONE_SCREEN = (query: string) => query.includes('max-width');
