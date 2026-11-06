import { useEffect, useState } from 'react';
import { chooseMode, currentMode, savedMode, type Mode } from '../lib/theme';

/**
 * The Day / Dark switch in the top bar. It shows the mode you would switch to: a sun while it is dark, a moon while
 * it is light. Until someone taps it, the app follows the phone's setting, including when that changes at sunset.
 */
export function ThemeToggle() {
  const [mode, setMode] = useState<Mode>(currentMode);

  useEffect(() => {
    if (savedMode()) return;
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!query) return;
    const follow = () => setMode(query.matches ? 'dark' : 'light');
    query.addEventListener('change', follow);
    return () => query.removeEventListener('change', follow);
  }, []);

  const next: Mode = mode === 'dark' ? 'light' : 'dark';
  const label = next === 'light' ? 'Day' : 'Dark';

  return (
    <button
      type="button"
      className="btn btn-quiet theme-toggle"
      aria-label={`Switch to ${next === 'light' ? 'day' : 'dark'} mode`}
      title={`Switch to ${next === 'light' ? 'day' : 'dark'} mode`}
      onClick={() => {
        chooseMode(next);
        setMode(next);
      }}
    >
      {next === 'light' ? <Sun /> : <Moon />}
      <span className="theme-toggle-label">{label}</span>
    </button>
  );
}

function Sun() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function Moon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />
    </svg>
  );
}
