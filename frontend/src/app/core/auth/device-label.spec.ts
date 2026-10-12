import { describe, expect, it } from 'vitest';

import { deviceLabel } from './device-label';

/**
 * The session label.
 *
 * <p>It matters more than its size suggests: it is what the sessions screen shows for each
 * entry, and because an access token carries no reference to the refresh family it came
 * from, matching on it is the only way a client can point at its own session in that list.
 * A label that changed between sign-ins, or that said "Safari" for Chrome, would make that
 * screen actively misleading.
 */
describe('deviceLabel', () => {
  const CASES: [string, string, string][] = [
    [
      'Chrome on Windows',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
      'Chrome sur Windows',
    ],
    [
      'Safari on iPhone',
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
      'Safari sur iPhone',
    ],
    [
      'Firefox on Mac',
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0',
      'Firefox sur Mac',
    ],
    [
      'Chrome on Android',
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36',
      'Chrome sur Android',
    ],
    [
      // Every Chromium browser also claims "Chrome", and Chrome itself claims "Safari", so
      // the order the patterns are tested in is the whole of the logic. Edge is the case
      // that catches a wrong order.
      'Edge on Windows',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0',
      'Edge sur Windows',
    ],
    [
      'Firefox on iOS, which is Safari underneath',
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/133.0 Mobile/15E148 Safari/605.1.15',
      'Firefox sur iPhone',
    ],
    [
      // iPadOS reports "Macintosh" in desktop mode. A tablet listed as a Mac is the kind of
      // wrong detail that makes a user distrust the whole screen.
      'iPad in desktop mode',
      'Mozilla/5.0 (iPad; CPU OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/604.1',
      'Safari sur iPad',
    ],
  ];

  it.each(CASES)('names %s', (_name, userAgent, expected) => {
    expect(deviceLabel(userAgent)).toBe(expected);
  });

  it('is stable across calls, which is what makes matching work', () => {
    const [, userAgent] = CASES[0];
    expect(deviceLabel(userAgent)).toBe(deviceLabel(userAgent));
  });

  it('falls back rather than producing an empty label', () => {
    // A bot, a webview, or a browser that has stopped sending a useful User-Agent — which
    // is where the platform is heading. An empty string here would render as a blank row.
    expect(deviceLabel('')).toBe('Navigateur web');
    expect(deviceLabel('something-nobody-has-seen')).toBe('Navigateur web');
  });

  it('names the platform alone when the browser is unrecognisable', () => {
    expect(deviceLabel('Mozilla/5.0 (Windows NT 10.0) SomeNewEngine/1.0')).toBe('Windows');
  });
});
