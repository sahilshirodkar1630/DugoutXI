import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { renderSignedIn, testUser } from '../test/render';
import { ChangePasswordForm } from './ChangePasswordForm';

/**
 * Changing a password. The backend signs every other session out and hands back a new token, which must replace
 * the old one, or this device would be signed out on its next request.
 */
afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

async function fill(current: string, next: string, again: string) {
  await userEvent.type(screen.getByLabelText(/Current password/), current);
  // The first box carries a hint in its label, so the two are told apart by order.
  const [first, second] = screen.getAllByLabelText(/^New password/);
  await userEvent.type(first, next);
  await userEvent.type(second, again);
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
}

describe('ChangePasswordForm', () => {
  it('does not send two new passwords that differ', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderSignedIn(<ChangePasswordForm />);

    await fill('old-password', 'new-password-1', 'new-password-2');

    expect(screen.getByText('The two new passwords do not match.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('swaps in the new token the backend returns', async () => {
    tokenStore.set('old-token');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(JSON.stringify({ token: 'new-token', user: testUser })),
    } as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);
    renderSignedIn(<ChangePasswordForm />);

    await fill('old-password', 'new-password', 'new-password');

    await waitFor(() => expect(tokenStore.get()).toBe('new-token'));
    expect(await screen.findByText(/Password changed/)).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(String(url)).toContain('/api/me/password');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      currentPassword: 'old-password',
      newPassword: 'new-password',
    });
  });
});
