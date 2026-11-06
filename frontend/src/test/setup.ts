import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

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
