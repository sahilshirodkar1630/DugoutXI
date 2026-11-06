import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import type { User } from '../api/types';

/**
 * Helpers for component tests.
 *
 * The API is stubbed at `fetch` rather than at the endpoint functions, so the real client runs: the token header,
 * the error shape and the JSON handling are all exercised exactly as they are in the browser.
 */

export const testUser: User = {
  id: 1,
  name: 'Sahil',
  displayName: 'Sahil',
  email: 'sahil@example.com',
  phoneNumber: null,
  photoUrl: null,
  upiId: 'sahil@okbank',
  role: 'USER',
  mustChangePassword: false,
};

/** Routes a stubbed fetch by URL, so one test can set up everything a screen asks for. */
export function stubApi(routes: Record<string, unknown>) {
  // The second parameter is declared even though the stub ignores it, so tests can read what was sent.
  const fetchMock = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const match = Object.keys(routes).find((path) => url.includes(path));
    if (!match) {
      return {
        ok: false,
        status: 404,
        text: () => Promise.resolve(JSON.stringify({ detail: `No stub for ${url}` })),
      } as unknown as Response;
    }
    return {
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify(routes[match])),
    } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** Renders inside a router, without the auth provider, for a component that does not need a signed-in user. */
export function renderWithRouter(element: ReactElement, path = '/') {
  return render(<MemoryRouter initialEntries={[path]}>{element}</MemoryRouter>);
}

/** Renders inside both the router and the auth provider, for a screen that reads the signed-in user. */
export function renderSignedIn(element: ReactElement, path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>{element}</AuthProvider>
    </MemoryRouter>,
  );
}
