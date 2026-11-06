import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { wakeTiming } from '../api/client';

/**
 * Test setup.
 *
 * Every test starts with an empty DOM, an empty local storage and no leftover fetch stub, so one test cannot leave
 * a signed-in session or a canned response behind for the next.
 */
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

// A sleeping server is waited for in the app; in tests nothing is asleep, so give up at once rather than hang.
beforeEach(() => {
  wakeTiming.limitMs = 0;
  wakeTiming.pollMs = 1;
});
