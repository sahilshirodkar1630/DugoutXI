/**
 * Day or dark.
 *
 * Until somebody chooses, the app follows the phone's own setting. Once they tap the switch their choice is kept in
 * this browser and wins over the phone's setting. It works by setting data-theme on <html>, which the stylesheet
 * already reads. index.html applies a saved choice before the page draws, so a dark-mode user never sees a white
 * flash on load.
 */
export type Mode = 'light' | 'dark';

const KEY = 'dugout.theme';
const TOP_BAR: Record<Mode, string> = { light: '#ffffff', dark: '#161b22' };

export function savedMode(): Mode | null {
  try {
    const value = localStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

export function systemMode(): Mode {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light';
}

/** What is on screen now: the saved choice, or the phone's setting. */
export function currentMode(): Mode {
  return savedMode() ?? systemMode();
}

/** Shows and remembers a mode. */
export function chooseMode(mode: Mode): void {
  document.documentElement.dataset.theme = mode;
  try {
    localStorage.setItem(KEY, mode);
  } catch {
    // Private browsing: the choice lasts until the page is closed.
  }
  // The phone's browser bar follows the app's top bar.
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
    meta.setAttribute('content', TOP_BAR[mode]);
  });
}
