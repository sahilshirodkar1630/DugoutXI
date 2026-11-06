import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SERVER_WAKING_MESSAGE, api, fileNameFrom, onUnauthorized, request, tokenStore, wakeTiming } from './client';

/**
 * What the API client has to get right, since every screen depends on it.
 *
 * The important ones are the token being attached, the backend's problem document becoming a message a user can
 * read, and a 401 ending the session wherever it happens — that last one is what stops a stale token leaving the
 * app in a half-signed-in state.
 */
function respondWith(status: number, body: unknown, ok = status < 400) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  } as unknown as Response);
}

describe('request', () => {
  beforeEach(() => {
    tokenStore.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends the stored token as a bearer header', async () => {
    tokenStore.set('a-token');
    const fetchMock = respondWith(200, { id: 1 });
    vi.stubGlobal('fetch', fetchMock);

    await api.get('/api/me');

    const [, init] = fetchMock.mock.calls[0];
    expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer a-token' });
  });

  it('leaves the token off a sign-in request, where there is not one yet', async () => {
    tokenStore.set('a-token');
    const fetchMock = respondWith(200, { token: 'new' });
    vi.stubGlobal('fetch', fetchMock);

    await request('/api/auth/login', { method: 'POST', body: {}, anonymous: true });

    const [, init] = fetchMock.mock.calls[0];
    expect((init as RequestInit).headers).not.toHaveProperty('Authorization');
  });

  it('turns the backend problem document into a readable message', async () => {
    vi.stubGlobal(
      'fetch',
      respondWith(409, { code: 'CONFLICT', detail: 'The contest has started; predictions are locked' }),
    );

    await expect(api.get('/api/contests/1')).rejects.toSatisfy((error: unknown) => {
      const apiError = error as ApiError;
      expect(apiError).toBeInstanceOf(ApiError);
      expect(apiError.status).toBe(409);
      expect(apiError.message).toBe('The contest has started; predictions are locked');
      expect(apiError.isUserFixable).toBe(true);
      return true;
    });
  });

  it('clears the token and announces a 401, so the whole app signs out', async () => {
    tokenStore.set('expired');
    vi.stubGlobal('fetch', respondWith(401, { detail: 'Login required' }));
    const signedOut = vi.fn();
    const stop = onUnauthorized(signedOut);

    await expect(api.get('/api/me')).rejects.toBeInstanceOf(ApiError);

    expect(tokenStore.get()).toBeNull();
    expect(signedOut).toHaveBeenCalledOnce();
    stop();
  });

  it('does not sign the user out when a sign-in attempt is refused', async () => {
    vi.stubGlobal('fetch', respondWith(401, { detail: 'Email or password is wrong' }));
    const signedOut = vi.fn();
    const stop = onUnauthorized(signedOut);

    await expect(
      request('/api/auth/login', { method: 'POST', body: {}, anonymous: true }),
    ).rejects.toBeInstanceOf(ApiError);

    expect(signedOut).not.toHaveBeenCalled();
    stop();
  });

  it('reports an unreachable backend as something a person can act on', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(api.get('/api/me')).rejects.toSatisfy((error: unknown) => {
      expect((error as ApiError).message).toContain('Could not reach the server');
      return true;
    });
  });

  it('falls back to a sensible message when the body is not a problem document', async () => {
    vi.stubGlobal('fetch', respondWith(403, 'nope', false));

    await expect(api.get('/api/admin/users')).rejects.toSatisfy((error: unknown) => {
      expect((error as ApiError).message).toBe('nope');
      return true;
    });
  });

  it('handles a 204 with no body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, status: 204, text: () => Promise.resolve('') } as unknown as Response),
    );

    await expect(api.del('/api/contests/1/join-requests/me')).resolves.toBeUndefined();
  });
});

describe('fileNameFrom', () => {
  it('prefers the UTF-8 name the backend sends, and falls back to the plain one', () => {
    expect(
      fileNameFrom(`attachment; filename="=?UTF-8?Q?x?="; filename*=UTF-8''Office%20League%20season%202026-11-06.xlsx`),
    ).toBe('Office League season 2026-11-06.xlsx');
    expect(fileNameFrom('attachment; filename="Season.xlsx"')).toBe('Season.xlsx');
    expect(fileNameFrom(null)).toBeNull();
  });
});

/**
 * The free host sleeps after a quiet spell; while it wakes, the site forwarding answers 502/503/504 or nothing.
 * Reads and signing in wait for the wake and go again; changes are not resent, because they may already have arrived.
 */
describe('a sleeping server', () => {
  beforeEach(() => {
    wakeTiming.limitMs = 1000;
    wakeTiming.pollMs = 5;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const reply = (status: number, body: unknown = {}) =>
    ({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(body)) }) as unknown as Response;

  function server(first: number) {
    let woken = false;
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (String(url).endsWith('/api/health')) {
        woken = true;
        return Promise.resolve(reply(200, { status: 'UP' }));
      }
      if (!woken) return Promise.resolve(reply(first));
      return Promise.resolve(reply(200, { ok: true, method: init?.method ?? 'GET' }));
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('waits for a read, then sends it again', async () => {
    const fetchMock = server(504);
    await expect(api.get('/api/leagues')).resolves.toEqual({ ok: true, method: 'GET' });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/leagues'))).toHaveLength(2);
  });

  it('retries signing in, which changes nothing', async () => {
    server(502);
    await expect(
      request('/api/auth/login', { method: 'POST', body: { email: 'a@b.c', password: 'x' }, anonymous: true }),
    ).resolves.toMatchObject({ ok: true });
  });

  it('does not resend a change, and says to try again', async () => {
    const fetchMock = server(504);
    await expect(api.post('/api/leagues/1/join-requests')).rejects.toSatisfy((error: unknown) => {
      expect((error as ApiError).message).toBe(SERVER_WAKING_MESSAGE);
      return true;
    });
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('join-requests'))).toHaveLength(1);
  });
});
