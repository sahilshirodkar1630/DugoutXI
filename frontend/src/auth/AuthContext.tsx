import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, onUnauthorized, tokenStore } from '../api/client';
import { auth, users } from '../api/endpoints';
import type { User } from '../api/types';
import { AuthContext, markSignedOut, type AuthState } from './authState';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // Check the stored token once, on start-up.
  useEffect(() => {
    let cancelled = false;
    if (!tokenStore.get()) {
      setLoading(false);
      return;
    }
    users
      .me()
      .then((me) => {
        if (!cancelled) setUser(me);
      })
      .catch((cause) => {
        // Only a token the backend refused is thrown away. A phone with no signal, or a server restarting, should
        // not sign anybody out: the next refresh tries the same token again.
        if (cause instanceof ApiError && cause.status === 401) tokenStore.clear();
        if (!cancelled) setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Any request that comes back unauthorized ends the session, wherever the user happens to be.
  useEffect(() => onUnauthorized(() => setUser(null)), []);

  const signIn = useCallback(async (email: string, password: string) => {
    const response = await auth.login({ email, password });
    tokenStore.set(response.token);
    setUser(response.user);
  }, []);

  const register = useCallback(
    async (name: string, email: string, password: string, displayName?: string) => {
      const response = await auth.register({ name, email, password, displayName });
      tokenStore.set(response.token);
      setUser(response.user);
    },
    [],
  );

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const response = await users.changePassword(currentPassword, newPassword);
    tokenStore.set(response.token);
    setUser(response.user);
  }, []);

  const signOut = useCallback(() => {
    markSignedOut();
    tokenStore.clear();
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, signIn, register, signOut, changePassword, replaceUser: setUser }),
    [user, loading, signIn, register, signOut, changePassword],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
