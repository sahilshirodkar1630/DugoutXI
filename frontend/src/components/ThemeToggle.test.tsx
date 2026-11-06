import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { ThemeToggle } from './ThemeToggle';

/** The Day / Dark switch: it flips the page between the two and remembers the choice in this browser. */
afterEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

describe('ThemeToggle', () => {
  it('switches to dark and back, remembering each choice', async () => {
    localStorage.setItem('dugout.theme', 'light');
    render(<ThemeToggle />);

    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('dugout.theme')).toBe('dark');

    await userEvent.click(screen.getByRole('button', { name: 'Switch to day mode' }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem('dugout.theme')).toBe('light');
  });

  it('starts from the phone setting when nothing was chosen', () => {
    render(<ThemeToggle />);
    // jsdom reports no dark preference, so the switch offers dark.
    expect(screen.getByRole('button', { name: 'Switch to dark mode' })).toBeInTheDocument();
    expect(localStorage.getItem('dugout.theme')).toBeNull();
  });
});
