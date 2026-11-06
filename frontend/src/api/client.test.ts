import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, fileNameFrom, onUnauthorized, request, tokenStore } from './client';

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
