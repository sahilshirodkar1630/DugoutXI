import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tokenStore } from '../api/client';
import { renderSignedIn, testUser } from '../test/render';
import { SignInPage } from './SignInPage';

/**
 * Signing in and signing up.
 *
 * The thing worth guarding is that a successful sign-in stores the token, because everything else in the app
 * depends on it, and that a refused one shows the backend's own message rather than a generic failure.
 */

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubAuth(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: status < 400,
    status,
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('SignInPage', () => {
  it('stores the token that comes back from a successful sign-in', async () => {
    const fetchMock = stubAuth(200, { token: 'signed-in-token', user: testUser });
    renderSignedIn(<SignInPage />);

    await userEvent.type(screen.getByLabelText('Email'), 'sahil@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/), 'password123');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(tokenStore.get()).toBe('signed-in-token'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/login');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      email: 'sahil@example.com',
      password: 'password123',
    });
  });

  it('shows the reason a sign-in was refused', async () => {
    stubAuth(401, { detail: 'Email or password is wrong' });
    renderSignedIn(<SignInPage />);

    await userEvent.type(screen.getByLabelText('Email'), 'sahil@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/), 'wrong-one');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is wrong');
    expect(tokenStore.get()).toBeNull();
  });

  it('asks for a name when registering, and signs the new user straight in', async () => {
    const fetchMock = stubAuth(201, { token: 'fresh-token', user: testUser });
    renderSignedIn(<SignInPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Create one' }));
    await userEvent.type(screen.getByLabelText('Your name'), 'Sahil');
    await userEvent.type(screen.getByLabelText('Email'), 'sahil@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/), 'password123');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(tokenStore.get()).toBe('fresh-token'));
    const [url] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/register');
  });

  it('leaves the display name out when it was not filled in, so the backend uses the name', async () => {
    const fetchMock = stubAuth(201, { token: 't', user: testUser });
    renderSignedIn(<SignInPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Create one' }));
    await userEvent.type(screen.getByLabelText('Your name'), 'Sahil Shirodkar');
    await userEvent.type(screen.getByLabelText('Email'), 'sahil@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/), 'password123');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => {
      const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
      expect(body.displayName).toBeUndefined();
      expect(body.name).toBe('Sahil Shirodkar');
    });
  });

  it('opens on the register form, saying where it leads, for somebody following a contest invite', () => {
    stubAuth(200, {});
    renderSignedIn(<SignInPage />, '/sign-in?next=%2Fcontests%2F9');

    expect(screen.getByText(/invited to contest #9/)).toBeInTheDocument();
    expect(screen.getByLabelText('Your name')).toBeInTheDocument();
  });

  it('asks the admins for a reset and answers the same whatever the email', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 202,
      text: () => Promise.resolve(''),
    } as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);
    renderSignedIn(<SignInPage />);

    await userEvent.type(screen.getByLabelText('Email'), 'sahil@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }));
    expect(screen.getByLabelText(/Your email/)).toHaveValue('sahil@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Ask for a reset' }));

    expect(await screen.findByText(/the admins have been told/)).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/auth/forgot-password');
    expect((init as RequestInit).headers).not.toHaveProperty('Authorization');
  });
});
