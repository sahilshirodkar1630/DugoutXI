import { createContext } from 'react';
import type { User } from '../api/types';

/**
 * Who is signed in.
 *
 * The token lives in local storage so a refresh does not sign the user out, but it is never trusted on its own:
 * on start-up the app asks the backend who the token belongs to. That way a token for a deleted or disabled account,
 * or one signed with an old secret, fails immediately rather than leaving a half-working session. The backend reads
 * the role from the database on every request too, so an admin flag in a stale token means nothing.
 */
export interface AuthState {
  user: User | null;
  /** True until the stored token has been checked, so routes do not redirect before the answer is in. */
  loading: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string, displayName?: string) => Promise<void>;
  signOut: () => void;
  /** Changes the password and swaps in the new token: every other session is signed out by the backend. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  /** Called after the profile screen saves, so the header shows the new name at once. */
  replaceUser: (user: User) => void;
}

export const AuthContext = createContext<AuthState | null>(null);

/**
 * Set when the user signs out on purpose, and read once by the redirect to the sign-in page: a deliberate sign-out
 * should not offer to take the next person back to the page the last one was on.
 */
let signedOutOnPurpose = false;
export function markSignedOut() {
  signedOutOnPurpose = true;
}
export function consumeSignedOut(): boolean {
  const was = signedOutOnPurpose;
  signedOutOnPurpose = false;
  return was;
}
