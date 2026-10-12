/**
 * A short name for the browser this session is running in, sent as `deviceLabel`.
 *
 * <p>Worth sending rather than leaving null, for two reasons. It is what the sessions
 * screen lists, and "session inconnue, créée le 3 mars" tells a user nothing about whether
 * to end it. And the API documents it as the way a client identifies its own entry in that
 * list: an access token carries no reference to the refresh family it came from, so there
 * is no id to match on and the label is the only handle there is.
 *
 * <p>Derived rather than stored, so the same browser produces the same label on every sign
 * in and the match still works after the session has been restored from storage. The
 * consequence is that two sessions from the same browser and platform are indistinguishable
 * — the sessions screen handles that by marking neither, rather than marking the wrong one.
 *
 * <p>Deliberately coarse. The full User-Agent is already recorded server-side and shown on
 * that screen for anyone who wants the detail; this is the part a person recognises.
 */
export function deviceLabel(userAgent = globalThis.navigator?.userAgent ?? ''): string {
  const browser = browserOf(userAgent);
  const platform = platformOf(userAgent);

  if (!browser && !platform) {
    return 'Navigateur web';
  }
  if (!platform) {
    return browser;
  }
  return browser ? `${browser} sur ${platform}` : platform;
}

/**
 * Order matters and is the whole difficulty: every Chromium browser also claims "Chrome",
 * and Chrome itself claims "Safari". So the specific names are tested before the generic
 * ones, and Safari last of all.
 */
function browserOf(userAgent: string): string {
  const candidates: [RegExp, string][] = [
    [/Edg\//, 'Edge'],
    [/OPR\/|Opera/, 'Opera'],
    [/SamsungBrowser/, 'Samsung Internet'],
    [/Firefox\/|FxiOS/, 'Firefox'],
    [/CriOS|Chrome\//, 'Chrome'],
    [/Safari\//, 'Safari'],
  ];
  for (const [pattern, name] of candidates) {
    if (pattern.test(userAgent)) {
      return name;
    }
  }
  return '';
}

function platformOf(userAgent: string): string {
  const candidates: [RegExp, string][] = [
    // iPad before Mac: iPadOS reports "Macintosh" in desktop mode, and a tablet called a
    // Mac in the list is the kind of wrong detail that makes a user distrust the screen.
    [/iPad/, 'iPad'],
    [/iPhone|iPod/, 'iPhone'],
    [/Android/, 'Android'],
    [/Windows/, 'Windows'],
    [/Macintosh|Mac OS X/, 'Mac'],
    [/CrOS/, 'ChromeOS'],
    [/Linux/, 'Linux'],
  ];
  for (const [pattern, name] of candidates) {
    if (pattern.test(userAgent)) {
      return name;
    }
  }
  return '';
}
